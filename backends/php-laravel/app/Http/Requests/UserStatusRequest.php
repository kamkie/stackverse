<?php

namespace App\Http\Requests;

use App\Support\BadRequestProblem;
use Illuminate\Contracts\Validation\Validator;

class UserStatusRequest extends ContractFormRequest
{
    protected function prepareForValidation(): void
    {
        $this->merge([
            'status' => $this->input('status'),
            'reason' => is_string($this->input('reason')) ? trim($this->input('reason')) : $this->input('reason'),
        ]);
    }

    public function rules(): array
    {
        return [
            'status' => ['required', 'in:active,blocked'],
            'reason' => ['required_if:status,blocked', 'nullable', 'string', 'max:1000'],
        ];
    }

    public function messages(): array
    {
        return [
            'reason.required_if' => 'validation.block.reason.required',
            'reason.string' => 'validation.block.reason.required',
            'reason.max' => 'validation.block.reason.too-long',
        ];
    }

    protected function failedValidation(Validator $validator): never
    {
        if ($validator->errors()->has('status')) {
            throw new BadRequestProblem('status is required');
        }

        parent::failedValidation($validator);
    }
}
