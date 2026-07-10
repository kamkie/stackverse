<?php

namespace App\Http\Requests;

class BookmarkStatusRequest extends ContractFormRequest
{
    protected function prepareForValidation(): void
    {
        $this->merge(['note' => $this->input('note')]);
    }

    public function rules(): array
    {
        return [
            'status' => ['required', 'in:active,hidden'],
            'note' => ['nullable', 'string', 'max:1000'],
        ];
    }

    public function messages(): array
    {
        return [
            'status.required' => 'validation.bookmark-status.invalid',
            'status.in' => 'validation.bookmark-status.invalid',
            'note.string' => 'validation.bookmark-status.note.too-long',
            'note.max' => 'validation.bookmark-status.note.too-long',
        ];
    }
}
