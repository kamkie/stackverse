<?php

namespace App\Http\Requests;

use App\Support\ValidationProblem;
use Illuminate\Contracts\Validation\Validator;
use Illuminate\Foundation\Http\FormRequest;

abstract class ContractFormRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    protected function failedValidation(Validator $validator): never
    {
        $violations = [];
        foreach ($validator->errors()->messages() as $field => $messages) {
            foreach ($messages as $messageKey) {
                $violation = ['field' => $this->contractField($field), 'messageKey' => $messageKey];
                if (! in_array($violation, $violations, true)) {
                    $violations[] = $violation;
                }
            }
        }

        throw new ValidationProblem($violations);
    }

    protected function contractField(string $field): string
    {
        return $field;
    }

    /**
     * @return array<string, mixed>
     */
    public function contractData(): array
    {
        return $this->validated();
    }
}
