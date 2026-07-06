<?php

namespace App\Services;

use Illuminate\Database\ConnectionInterface;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class AuditService
{
    public function record(
        string $actor,
        string $action,
        string $targetType,
        string $targetId,
        ?array $detail = null,
        ?ConnectionInterface $db = null,
    ): void {
        ($db ?? DB::connection())->insert(
            'insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
             values (?, ?, ?, ?, ?, ?::jsonb, clock_timestamp())',
            [
                (string) Str::uuid(),
                $actor,
                $action,
                $targetType,
                $targetId,
                $detail === null ? null : json_encode($detail, JSON_UNESCAPED_SLASHES),
            ],
        );
    }
}
