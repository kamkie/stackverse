<?php

namespace App\Http\Requests;

class ResolutionRequest extends ContractFormRequest
{
    protected function prepareForValidation(): void
    {
        $this->merge(['note' => $this->input('note')]);
    }

    public function rules(): array
    {
        return [
            'resolution' => ['required', 'in:open,dismissed,actioned'],
            'note' => ['nullable', 'string', 'max:1000'],
        ];
    }

    public function messages(): array
    {
        return [
            'resolution.required' => 'validation.resolution.invalid',
            'resolution.in' => 'validation.resolution.invalid',
            'note.string' => 'validation.resolution.note.too-long',
            'note.max' => 'validation.resolution.note.too-long',
        ];
    }
}
