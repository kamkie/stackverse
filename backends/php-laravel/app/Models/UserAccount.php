<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Relations\HasMany;

class UserAccount extends BaseModel
{
    public $incrementing = false;

    public $timestamps = false;

    protected $primaryKey = 'username';

    protected $keyType = 'string';

    protected $guarded = [];

    protected function casts(): array
    {
        return ['first_seen' => 'immutable_datetime', 'last_seen' => 'immutable_datetime'];
    }

    public function bookmarks(): HasMany
    {
        return $this->hasMany(Bookmark::class, 'owner', 'username');
    }
}
