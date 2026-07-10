<?php

namespace App\Http\Resources;

use App\Support\Wire;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class ReportResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return Wire::omitNulls([
            'id' => $this->id,
            'bookmarkId' => $this->bookmark_id,
            'reporter' => $this->reporter,
            'reason' => $this->reason,
            'comment' => $this->comment,
            'status' => $this->status,
            'createdAt' => Wire::iso($this->created_at),
            'resolvedBy' => $this->resolved_by,
            'resolvedAt' => $this->resolved_at === null ? null : Wire::iso($this->resolved_at),
            'resolutionNote' => $this->resolution_note,
        ]);
    }
}
