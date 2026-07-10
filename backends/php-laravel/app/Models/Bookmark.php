<?php

namespace App\Models;

use App\Support\Wire;
use Illuminate\Database\Eloquent\Casts\Attribute;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Bookmark extends BaseModel
{
    public $incrementing = false;

    protected $keyType = 'string';

    protected $guarded = [];

    protected function casts(): array
    {
        return ['created_at' => 'immutable_datetime', 'updated_at' => 'immutable_datetime'];
    }

    protected function tags(): Attribute
    {
        return Attribute::make(
            get: static fn (mixed $value): array => Wire::pgTextArrayToList((string) $value),
            set: static fn (mixed $value): string => Wire::pgTextArray(is_array($value) ? $value : []),
        );
    }

    public function reports(): HasMany
    {
        return $this->hasMany(Report::class);
    }
}
