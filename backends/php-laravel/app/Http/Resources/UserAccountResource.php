<?php

namespace App\Http\Resources;

use App\Support\Wire;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class UserAccountResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return Wire::omitNulls([
            'username' => $this->username,
            'firstSeen' => Wire::iso($this->first_seen),
            'lastSeen' => Wire::iso($this->last_seen),
            'status' => $this->status,
            'blockedReason' => $this->blocked_reason,
            'bookmarkCount' => (int) $this->bookmarks_count,
        ]);
    }
}
