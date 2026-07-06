<?php

namespace App\Support;

class ForbiddenProblem extends ApiProblem
{
    public function __construct(string $detail, ?string $detailKey = null)
    {
        parent::__construct(403, 'Forbidden', $detail, $detailKey);
    }
}
