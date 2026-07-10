<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Http\Requests\MessageRequest;
use App\Http\Resources\MessageResource;
use App\Models\Message;
use App\Services\AuditService;
use App\Services\I18nService;
use App\Support\ConflictProblem;
use App\Support\Logger;
use App\Support\NotFoundProblem;
use App\Support\Wire;
use Illuminate\Database\QueryException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class MessageController extends Controller
{
    public function __construct(private readonly I18nService $i18n, private readonly AuditService $audit) {}

    public function list(Request $request): Response
    {
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $key = Wire::singleParam($request, 'key');
        $language = Wire::singleParam($request, 'language');
        $q = Wire::singleParam($request, 'q');
        Wire::requireMaxLength($q, 200, 'q');

        $query = Message::query();
        if ($key !== null) {
            $query->where('key', $key);
        }
        if ($language !== null) {
            $query->where('language', $language);
        }
        if ($q !== null && trim($q) !== '') {
            $query->whereRaw('(position(lower(?) in lower(key)) > 0 or position(lower(?) in lower(text)) > 0)', [$q, $q]);
        }

        $total = (clone $query)->count();
        $items = $query->orderBy('key')->orderBy('language')->skip($page * $size)->take($size)->get();

        return Wire::etag($request, [
            'items' => $items->map(fn (Message $message): array => $this->toMessage($message, $request))->all(),
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
        $message = Message::find(Wire::parseUuid($id));
        if ($message === null) {
            throw new NotFoundProblem;
        }

        return Wire::etag($request, $this->toMessage($message, $request));
    }

    public function create(MessageRequest $request): JsonResponse
    {
        $caller = Caller::requireRole($request, 'admin');
        $input = $request->contractData();
        $message = DB::transaction(function () use ($caller, $input): Message {
            if (Message::query()->where('key', $input['key'])->where('language', $input['language'])->exists()) {
                throw $this->duplicate($input);
            }
            try {
                $message = Message::create([...$input, 'id' => (string) Str::uuid()]);
            } catch (QueryException $error) {
                if (($error->errorInfo[0] ?? null) === '23505') {
                    throw $this->duplicate($input);
                }
                throw $error;
            }
            $this->audit->record($caller->username, 'message.created', 'message', $message->id, $this->snapshot($message));

            return $message;
        });
        $this->logMessageEvent('message_created', 'Message created', $caller->username, $message);

        return response()->json($this->toMessage($message, $request), 201, ['Location' => "/api/v1/messages/$message->id"]);
    }

    public function update(MessageRequest $request, string $id): array
    {
        $caller = Caller::requireRole($request, 'admin');
        $messageId = Wire::parseUuid($id);
        $input = $request->contractData();
        $message = DB::transaction(function () use ($caller, $messageId, $input): Message {
            $message = Message::find($messageId);
            if ($message === null) {
                throw new NotFoundProblem;
            }
            if (Message::query()->where('key', $input['key'])->where('language', $input['language'])->whereKeyNot($messageId)->exists()) {
                throw $this->duplicate($input);
            }
            try {
                $message->fill($input)->save();
            } catch (QueryException $error) {
                if (($error->errorInfo[0] ?? null) === '23505') {
                    throw $this->duplicate($input);
                }
                throw $error;
            }
            $this->audit->record($caller->username, 'message.updated', 'message', $message->id, $this->snapshot($message));

            return $message->refresh();
        });
        $this->logMessageEvent('message_updated', 'Message updated', $caller->username, $message);

        return $this->toMessage($message, $request);
    }

    public function delete(Request $request, string $id): JsonResponse
    {
        $caller = Caller::requireRole($request, 'admin');
        $messageId = Wire::parseUuid($id);
        $message = DB::transaction(function () use ($caller, $messageId): Message {
            $message = Message::find($messageId);
            if ($message === null) {
                throw new NotFoundProblem;
            }
            $this->audit->record($caller->username, 'message.deleted', 'message', $message->id, $this->snapshot($message));
            $message->delete();

            return $message;
        });
        $this->logMessageEvent('message_deleted', 'Message deleted', $caller->username, $message);

        return response()->json(null, 204);
    }

    private function toMessage(Message $message, Request $request): array
    {
        return (new MessageResource($message))->resolve($request);
    }

    private function duplicate(array $input): ConflictProblem
    {
        return new ConflictProblem("A message with key '{$input['key']}' and language '{$input['language']}' already exists.");
    }

    private function snapshot(Message $message): array
    {
        return [
            'key' => $message->key,
            'language' => $message->language,
            'text' => $message->text,
            'description' => $message->description,
        ];
    }

    private function logMessageEvent(string $event, string $description, string $actor, Message $message): void
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
