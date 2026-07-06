<?php

namespace App\Support;

class BadRequestProblem extends ApiProblem
{
    public function __construct(string $detail)
    {
        parent::__construct(400, 'Bad Request', $detail);
    }
}
