<?php

namespace App\Support;

class Validator
{
    /**
     * @var list<array{field: string, messageKey: string}>
     */
    private array $violations = [];

    public function reject(string $field, string $messageKey): void
    {
        $this->violations[] = ['field' => $field, 'messageKey' => $messageKey];
    }

    public function check(bool $condition, string $field, string $messageKey): void
    {
        if (! $condition) {
            $this->reject($field, $messageKey);
        }
    }

    public function throwIfInvalid(): void
    {
        if ($this->violations !== []) {
            throw new ValidationProblem($this->violations);
        }
    }
}
