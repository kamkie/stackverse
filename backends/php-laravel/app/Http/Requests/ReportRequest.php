<?php

namespace App\Http\Requests;

class ReportRequest extends ContractFormRequest
{
    protected function prepareForValidation(): void
    {
        $this->merge(['comment' => $this->input('comment')]);
    }

    public function rules(): array
    {
        return [
            'reason' => ['required', 'in:spam,offensive,broken-link,other'],
            'comment' => ['nullable', 'string', 'max:1000'],
        ];
    }

    public function messages(): array
    {
        return [
            'reason.required' => 'validation.report.reason.invalid',
            'reason.in' => 'validation.report.reason.invalid',
            'comment.string' => 'validation.report.comment.too-long',
            'comment.max' => 'validation.report.comment.too-long',
        ];
    }
}
