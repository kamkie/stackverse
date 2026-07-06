<?php

namespace Tests\Unit;

use App\Services\I18nService;
use PHPUnit\Framework\TestCase;

class I18nServiceTest extends TestCase
{
    public function test_accept_language_orders_by_quality_and_keeps_primary_subtag(): void
    {
        $service = new I18nService;

        self::assertSame(['pl', 'en', 'de'], $service->parseAcceptLanguage('en-US;q=0.7, pl-PL;q=0.9, de;q=0.1'));
    }

    public function test_accept_language_skips_invalid_entries(): void
    {
        $service = new I18nService;

        self::assertSame(['en'], $service->parseAcceptLanguage('*, en;q=0.8, bad_tag'));
    }
}
