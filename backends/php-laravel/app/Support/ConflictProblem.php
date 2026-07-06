<?php

namespace App\Support;

class ConflictProblem extends ApiProblem
{
    public function __construct(string $detail, ?string $detailKey = null)
    {
        parent::__construct(409, 'Conflict', $detail, $detailKey);
    }
}
