<?php

namespace App\Support;

class UnauthorizedProblem extends ApiProblem
{
    public function __construct(string $detail = 'Authentication is required.')
    {
        parent::__construct(401, 'Unauthorized', $detail);
    }
}
