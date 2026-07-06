<?php

namespace App\Support;

class NotFoundProblem extends ApiProblem
{
    public function __construct()
    {
        parent::__construct(404, 'Not Found');
    }
}
