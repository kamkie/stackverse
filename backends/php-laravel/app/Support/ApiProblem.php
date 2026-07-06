<?php

namespace App\Support;

use RuntimeException;

class ApiProblem extends RuntimeException
{
    public function __construct(
        public readonly int $status,
        public readonly string $title,
        public readonly ?string $detail = null,
        public readonly ?string $detailKey = null,
    ) {
        parent::__construct($detail ?? $title);
    }
}
