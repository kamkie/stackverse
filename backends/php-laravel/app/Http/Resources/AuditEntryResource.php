<?php

namespace App\Http\Resources;

use App\Support\Wire;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class AuditEntryResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return Wire::omitNulls([
            'id' => $this->id,
            'actor' => $this->actor,
            'action' => $this->action,
            'targetType' => $this->target_type,
            'targetId' => $this->target_id,
            'detail' => $this->detail,
            'createdAt' => Wire::iso($this->created_at),
        ]);
    }
}
