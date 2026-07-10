<?php

namespace App\Models;

class AuditEntry extends BaseModel
{
    public $incrementing = false;

    public $timestamps = false;

    protected $keyType = 'string';

    protected $guarded = [];

    protected function casts(): array
    {
        return ['created_at' => 'immutable_datetime', 'detail' => 'array'];
    }
}
