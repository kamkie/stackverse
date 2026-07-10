<?php

use App\Http\Controllers\AdminController;
use App\Http\Controllers\BookmarkController;
use App\Http\Controllers\MessageController;
use App\Http\Controllers\MetaController;
use App\Http\Controllers\ModerationController;
use Illuminate\Support\Facades\Route;

Route::get('/healthz', [MetaController::class, 'healthz']);
Route::get('/readyz', [MetaController::class, 'readyz']);

Route::get('/api/v1/bookmarks', [BookmarkController::class, 'listV1']);
Route::get('/api/v2/bookmarks', [BookmarkController::class, 'listV2']);
Route::get('/api/v1/bookmarks/{id}', [BookmarkController::class, 'get']);

Route::get('/api/v1/messages', [MessageController::class, 'list']);
Route::get('/api/v1/messages/bundle', [MessageController::class, 'bundle']);
Route::get('/api/v1/messages/{id}', [MessageController::class, 'get']);

Route::middleware('auth:api')->group(function (): void {
    Route::get('/api/v1/me', [MetaController::class, 'me']);
    Route::post('/api/v1/bookmarks', [BookmarkController::class, 'create']);
    Route::put('/api/v1/bookmarks/{id}', [BookmarkController::class, 'update']);
    Route::delete('/api/v1/bookmarks/{id}', [BookmarkController::class, 'delete']);
    Route::get('/api/v1/tags', [BookmarkController::class, 'tags']);

    Route::post('/api/v1/bookmarks/{id}/reports', [ModerationController::class, 'reportBookmark']);
    Route::get('/api/v1/reports', [ModerationController::class, 'listMine']);
    Route::put('/api/v1/reports/{id}', [ModerationController::class, 'updateMine']);
    Route::delete('/api/v1/reports/{id}', [ModerationController::class, 'withdrawMine']);
});

Route::middleware(['auth:api', 'role:admin'])->group(function (): void {
    Route::post('/api/v1/messages', [MessageController::class, 'create']);
    Route::put('/api/v1/messages/{id}', [MessageController::class, 'update']);
    Route::delete('/api/v1/messages/{id}', [MessageController::class, 'delete']);

    Route::get('/api/v1/admin/users', [AdminController::class, 'users']);
    Route::get('/api/v1/admin/users/{username}', [AdminController::class, 'user']);
    Route::put('/api/v1/admin/users/{username}/status', [AdminController::class, 'setUserStatus']);
    Route::get('/api/v1/admin/audit-log', [AdminController::class, 'auditLog']);
});

Route::middleware(['auth:api', 'role:moderator'])->group(function (): void {
    Route::get('/api/v1/admin/reports', [ModerationController::class, 'listAdmin']);
    Route::put('/api/v1/admin/reports/{id}', [ModerationController::class, 'resolve']);
    Route::put('/api/v1/admin/bookmarks/{id}/status', [ModerationController::class, 'setBookmarkStatus']);
    Route::get('/api/v1/admin/stats', [AdminController::class, 'stats']);
});
