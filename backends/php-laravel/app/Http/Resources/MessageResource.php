<?php

namespace App\Http\Resources;

use App\Support\Wire;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class MessageResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return Wire::omitNulls([
            'id' => $this->id,
            'key' => $this->key,
            'language' => $this->language,
            'text' => $this->text,
            'description' => $this->description,
            'createdAt' => Wire::iso($this->created_at),
            'updatedAt' => Wire::iso($this->updated_at),
        ]);
    }
}
