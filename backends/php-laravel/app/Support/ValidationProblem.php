<?php

namespace App\Support;

use RuntimeException;

class ValidationProblem extends RuntimeException
{
    /**
     * @param  list<array{field: string, messageKey: string}>  $violations
     */
    public function __construct(public readonly array $violations)
    {
        parent::__construct('Validation failed');
    }
}
