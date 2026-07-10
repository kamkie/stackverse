<?php

namespace App\Http\Requests;

use App\Support\BadRequestProblem;
use Closure;
use Illuminate\Contracts\Validation\Validator;

class BookmarkRequest extends ContractFormRequest
{
    protected function prepareForValidation(): void
    {
        $tags = $this->input('tags', []);
        if (is_array($tags)) {
            $tags = array_values(array_unique(array_map(
                static fn (mixed $tag): mixed => is_string($tag) ? strtolower(trim($tag)) : $tag,
                $tags,
            ), SORT_REGULAR));
        }

        $this->merge([
            'url' => is_string($this->input('url')) ? trim($this->input('url')) : $this->input('url'),
            'title' => is_string($this->input('title')) ? trim($this->input('title')) : $this->input('title'),
            'notes' => $this->input('notes'),
            'tags' => $tags,
            'visibility' => $this->input('visibility', 'private'),
        ]);
    }

    public function rules(): array
    {
        return [
            'url' => [
                'required',
                'string',
                'max:2000',
                static function (string $attribute, mixed $value, Closure $fail): void {
                    if (! is_string($value) || filter_var($value, FILTER_VALIDATE_URL) === false) {
                        $fail('validation.url.invalid');

                        return;
                    }
                    $scheme = strtolower((string) parse_url($value, PHP_URL_SCHEME));
                    $host = parse_url($value, PHP_URL_HOST);
                    if (! in_array($scheme, ['http', 'https'], true) || ! is_string($host) || $host === '') {
                        $fail('validation.url.invalid');
                    }
                },
            ],
            'title' => ['required', 'string', 'max:200'],
            'notes' => ['nullable', 'string', 'max:4000'],
            'tags' => ['array', 'max:10'],
            'tags.*' => ['string', 'regex:/^[a-z0-9-]{1,30}$/'],
            'visibility' => ['required', 'in:private,public'],
        ];
    }

    public function messages(): array
    {
        return [
            'url.required' => 'validation.url.required',
            'url.string' => 'validation.url.required',
            'url.max' => 'validation.url.invalid',
            'title.required' => 'validation.title.required',
            'title.string' => 'validation.title.required',
            'title.max' => 'validation.title.too-long',
            'notes.string' => 'validation.notes.too-long',
            'notes.max' => 'validation.notes.too-long',
            'tags.array' => 'validation.tag.invalid',
            'tags.max' => 'validation.tags.too-many',
            'tags.*.string' => 'validation.tag.invalid',
            'tags.*.regex' => 'validation.tag.invalid',
        ];
    }

    protected function failedValidation(Validator $validator): never
    {
        if ($validator->errors()->has('visibility')) {
            $visibility = $this->input('visibility');
            $display = is_scalar($visibility) || $visibility === null ? (string) $visibility : get_debug_type($visibility);

            throw new BadRequestProblem("unknown visibility: $display");
        }

        parent::failedValidation($validator);
    }

    protected function contractField(string $field): string
    {
        return str_starts_with($field, 'tags.') ? 'tags' : $field;
    }
}
