<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Services\AuditService;
use App\Services\I18nService;
use App\Support\ConflictProblem;
use App\Support\Logger;
use App\Support\NotFoundProblem;
use App\Support\Validator;
use App\Support\Wire;
use Illuminate\Database\QueryException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class MessageController extends Controller
{
    private const KEY_PATTERN = '/^[a-z0-9-]+(\.[a-z0-9-]+)*$/';

    private const LANGUAGE_PATTERN = '/^[a-z]{2}$/';

    public function __construct(private readonly I18nService $i18n, private readonly AuditService $audit) {}

    public function list(Request $request): Response
    {
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $key = Wire::singleParam($request, 'key');
        $language = Wire::singleParam($request, 'language');
        $q = Wire::singleParam($request, 'q');
        Wire::requireMaxLength($q, 200, 'q');

        $conditions = ['true'];
        $params = [];
        if ($key !== null) {
            $conditions[] = 'key = ?';
            $params[] = $key;
        }
        if ($language !== null) {
            $conditions[] = 'language = ?';
            $params[] = $language;
        }
        if ($q !== null && trim($q) !== '') {
            $conditions[] = '(position(lower(?) in lower(key)) > 0 or position(lower(?) in lower(text)) > 0)';
            array_push($params, $q, $q);
        }
        $where = implode(' and ', $conditions);

        $items = DB::select(
            "select * from messages where $where order by key, language limit ? offset ?",
            [...$params, $size, $page * $size],
        );
        $total = (int) DB::selectOne("select count(*)::int as count from messages where $where", $params)->count;

        return Wire::etag($request, [
            'items' => array_map($this->toMessage(...), $items),
            'page' => $page,
            'size' => $size,
            'totalItems' => $total,
            'totalPages' => (int) ceil($total / $size),
        ]);
    }

    public function bundle(Request $request): Response
    {
        $language = $this->i18n->requestLanguage($request);

        return Wire::etag($request, [
            'language' => $language,
            'messages' => $this->i18n->bundle($language),
        ], ['Content-Language' => $language]);
    }

    public function get(Request $request, string $id): Response
    {
        $row = DB::selectOne('select * from messages where id = ?', [Wire::parseUuid($id)]);
        if ($row === null) {
            throw new NotFoundProblem;
        }

        return Wire::etag($request, $this->toMessage($row));
    }

    public function create(Request $request): JsonResponse
    {
        $caller = Caller::requireRole($request, 'admin');
        $input = $this->validateMessageInput(Wire::jsonBody($request));
        $message = DB::transaction(function () use ($caller, $input): object {
            if (DB::selectOne('select 1 from messages where key = ? and language = ?', [$input['key'], $input['language']]) !== null) {
                throw $this->duplicate($input);
            }
            try {
                $row = DB::selectOne(
                    'insert into messages (id, key, language, text, description, created_at, updated_at)
                     values (?, ?, ?, ?, ?, clock_timestamp(), clock_timestamp()) returning *',
                    [(string) Str::uuid(), $input['key'], $input['language'], $input['text'], $input['description']],
                );
            } catch (QueryException $error) {
                if (($error->errorInfo[0] ?? null) === '23505') {
                    throw $this->duplicate($input);
                }
                throw $error;
            }
            $this->audit->record($caller->username, 'message.created', 'message', $row->id, $this->snapshot($row));

            return $row;
        });
        $this->logMessageEvent('message_created', 'Message created', $caller->username, $message);

        return response()->json($this->toMessage($message), 201, ['Location' => "/api/v1/messages/$message->id"]);
    }

    public function update(Request $request, string $id): array
    {
        $caller = Caller::requireRole($request, 'admin');
        $messageId = Wire::parseUuid($id);
        $input = $this->validateMessageInput(Wire::jsonBody($request));
        $message = DB::transaction(function () use ($caller, $messageId, $input): object {
            if (DB::selectOne('select 1 from messages where id = ?', [$messageId]) === null) {
                throw new NotFoundProblem;
            }
            if (DB::selectOne('select 1 from messages where key = ? and language = ? and id <> ?', [$input['key'], $input['language'], $messageId]) !== null) {
                throw $this->duplicate($input);
            }
            try {
                $row = DB::selectOne(
                    'update messages set key = ?, language = ?, text = ?, description = ?, updated_at = clock_timestamp() where id = ? returning *',
                    [$input['key'], $input['language'], $input['text'], $input['description'], $messageId],
                );
            } catch (QueryException $error) {
                if (($error->errorInfo[0] ?? null) === '23505') {
                    throw $this->duplicate($input);
                }
                throw $error;
            }
            $this->audit->record($caller->username, 'message.updated', 'message', $row->id, $this->snapshot($row));

            return $row;
        });
        $this->logMessageEvent('message_updated', 'Message updated', $caller->username, $message);

        return $this->toMessage($message);
    }

    public function delete(Request $request, string $id): JsonResponse
    {
        $caller = Caller::requireRole($request, 'admin');
        $messageId = Wire::parseUuid($id);
        $message = DB::transaction(function () use ($caller, $messageId): object {
            $row = DB::selectOne('delete from messages where id = ? returning *', [$messageId]);
            if ($row === null) {
                throw new NotFoundProblem;
            }
            $this->audit->record($caller->username, 'message.deleted', 'message', $row->id, $this->snapshot($row));

            return $row;
        });
        $this->logMessageEvent('message_deleted', 'Message deleted', $caller->username, $message);

        return response()->json(null, 204);
    }

    private function validateMessageInput(array $body): array
    {
        $validator = new Validator;
        $key = is_string($body['key'] ?? null) ? trim($body['key']) : '';
        $validator->check(preg_match(self::KEY_PATTERN, $key) === 1 && mb_strlen($key) <= 150, 'key', 'validation.message.key.invalid');

        $language = is_string($body['language'] ?? null) ? trim($body['language']) : '';
        $validator->check(preg_match(self::LANGUAGE_PATTERN, $language) === 1, 'language', 'validation.message.language.invalid');

        $text = is_string($body['text'] ?? null) ? $body['text'] : '';
        $validator->check($text !== '', 'text', 'validation.message.text.required');
        $validator->check(mb_strlen($text) <= 2000, 'text', 'validation.message.text.too-long');

        $description = is_string($body['description'] ?? null) ? $body['description'] : null;
        $validator->check(mb_strlen($description ?? '') <= 1000, 'description', 'validation.message.description.too-long');
        $validator->throwIfInvalid();

        return ['key' => $key, 'language' => $language, 'text' => $text, 'description' => $description];
    }

    private function toMessage(object $row): array
    {
        return Wire::omitNulls([
            'id' => $row->id,
            'key' => $row->key,
            'language' => $row->language,
            'text' => $row->text,
            'description' => $row->description,
            'createdAt' => Wire::iso($row->created_at),
            'updatedAt' => Wire::iso($row->updated_at),
        ]);
    }

    private function duplicate(array $input): ConflictProblem
    {
        return new ConflictProblem("A message with key '{$input['key']}' and language '{$input['language']}' already exists.");
    }

    private function snapshot(object $message): array
    {
        return [
            'key' => $message->key,
            'language' => $message->language,
            'text' => $message->text,
            'description' => $message->description,
        ];
    }

    private function logMessageEvent(string $event, string $description, string $actor, object $message): void
    {
        Logger::event('info', $event, 'success', $description, [
            'actor' => $actor,
            'resource_type' => 'message',
            'resource_id' => $message->id,
            'message_key' => $message->key,
            'language' => $message->language,
        ]);
    }
}
