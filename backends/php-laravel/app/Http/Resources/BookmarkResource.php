<?php

namespace App\Http\Resources;

use App\Support\Wire;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class BookmarkResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return Wire::omitNulls([
            'id' => $this->id,
            'url' => $this->url,
            'title' => $this->title,
            'notes' => $this->notes,
            'tags' => $this->tags,
            'visibility' => $this->visibility,
            'status' => $this->status,
            'owner' => $this->owner,
            'createdAt' => Wire::iso($this->created_at),
            'updatedAt' => Wire::iso($this->updated_at),
        ]);
    }
}
