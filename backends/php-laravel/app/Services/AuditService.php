<?php

namespace App\Services;

use App\Models\AuditEntry;
use Illuminate\Support\Str;

class AuditService
{
    public function record(
        string $actor,
        string $action,
        string $targetType,
        string $targetId,
        ?array $detail = null,
    ): void {
        AuditEntry::create([
            'id' => (string) Str::uuid(),
            'actor' => $actor,
            'action' => $action,
            'target_type' => $targetType,
            'target_id' => $targetId,
            'detail' => $detail,
            'created_at' => now(),
        ]);
    }
}
