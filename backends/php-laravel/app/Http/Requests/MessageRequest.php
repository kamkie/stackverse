<?php

namespace App\Http\Requests;

class MessageRequest extends ContractFormRequest
{
    protected function prepareForValidation(): void
    {
        $this->merge([
            'key' => is_string($this->input('key')) ? trim($this->input('key')) : $this->input('key'),
            'language' => is_string($this->input('language')) ? trim($this->input('language')) : $this->input('language'),
            'text' => $this->input('text'),
            'description' => $this->input('description'),
        ]);
    }

    public function rules(): array
    {
        return [
            'key' => ['required', 'string', 'max:150', 'regex:/^[a-z0-9-]+(?:\.[a-z0-9-]+)*$/'],
            'language' => ['required', 'string', 'regex:/^[a-z]{2}$/'],
            'text' => ['required', 'string', 'max:2000'],
            'description' => ['nullable', 'string', 'max:1000'],
        ];
    }

    public function messages(): array
    {
        return [
            'key.required' => 'validation.message.key.invalid',
            'key.string' => 'validation.message.key.invalid',
            'key.max' => 'validation.message.key.invalid',
            'key.regex' => 'validation.message.key.invalid',
            'language.required' => 'validation.message.language.invalid',
            'language.string' => 'validation.message.language.invalid',
            'language.regex' => 'validation.message.language.invalid',
            'text.required' => 'validation.message.text.required',
            'text.string' => 'validation.message.text.required',
            'text.max' => 'validation.message.text.too-long',
            'description.string' => 'validation.message.description.too-long',
            'description.max' => 'validation.message.description.too-long',
        ];
    }
}
