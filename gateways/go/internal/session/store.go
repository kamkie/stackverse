// Package session stores gateway sessions and one-time OIDC state in Redis.
package session

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	SessionKeyPrefix = "stackverse:session:"
	StateKeyPrefix   = "stackverse:oauth-state:"
	SessionTTL       = 8 * time.Hour
	StateTTL         = 5 * time.Minute
)

type Data struct {
	Username     string    `json:"username"`
	AccessToken  string    `json:"accessToken"`
	RefreshToken string    `json:"refreshToken"`
	IDToken      string    `json:"idToken,omitempty"`
	ExpiresAt    time.Time `json:"expiresAt"`
	CreatedAt    time.Time `json:"createdAt"`
	UpdatedAt    time.Time `json:"updatedAt"`
}

type OAuthState struct {
	CodeVerifier string    `json:"codeVerifier"`
	CreatedAt    time.Time `json:"createdAt"`
}

type Store interface {
	LoadSession(context.Context, string) (Data, bool, error)
	SaveSession(context.Context, string, Data, time.Duration) error
	DeleteSession(context.Context, string) error
	SaveOAuthState(context.Context, string, OAuthState, time.Duration) error
	ConsumeOAuthState(context.Context, string) (OAuthState, bool, error)
}

type RedisStore struct {
	client *redis.Client
}

func NewRedisStore(ctx context.Context, redisURL string) (*RedisStore, error) {
	options, err := redisOptions(redisURL)
	if err != nil {
		return nil, err
	}
	client := redis.NewClient(options)
	if err := client.Ping(ctx).Err(); err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("redis ping: %w", err)
	}
	return &RedisStore{client: client}, nil
}

func (s *RedisStore) Close() error {
	return s.client.Close()
}

func (s *RedisStore) LoadSession(ctx context.Context, key string) (Data, bool, error) {
	raw, err := s.client.Get(ctx, SessionKeyPrefix+key).Bytes()
	if errors.Is(err, redis.Nil) {
		return Data{}, false, nil
	}
	if err != nil {
		return Data{}, false, err
	}
	var data Data
	if err := json.Unmarshal(raw, &data); err != nil {
		return Data{}, false, err
	}
	return data, true, nil
}

func (s *RedisStore) SaveSession(ctx context.Context, key string, data Data, ttl time.Duration) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return s.client.Set(ctx, SessionKeyPrefix+key, raw, ttl).Err()
}

func (s *RedisStore) DeleteSession(ctx context.Context, key string) error {
	return s.client.Del(ctx, SessionKeyPrefix+key).Err()
}

func (s *RedisStore) SaveOAuthState(ctx context.Context, state string, data OAuthState, ttl time.Duration) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return s.client.Set(ctx, StateKeyPrefix+state, raw, ttl).Err()
}

func (s *RedisStore) ConsumeOAuthState(ctx context.Context, state string) (OAuthState, bool, error) {
	key := StateKeyPrefix + state
	raw, err := s.client.Get(ctx, key).Bytes()
	if errors.Is(err, redis.Nil) {
		return OAuthState{}, false, nil
	}
	if err != nil {
		return OAuthState{}, false, err
	}
	if err := s.client.Del(ctx, key).Err(); err != nil {
		return OAuthState{}, false, err
	}
	var data OAuthState
	if err := json.Unmarshal(raw, &data); err != nil {
		return OAuthState{}, false, err
	}
	return data, true, nil
}

func NewOpaqueID() (string, error) {
	var bytes [32]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes[:]), nil
}

func redisOptions(redisURL string) (*redis.Options, error) {
	if strings.Contains(redisURL, "://") {
		return redis.ParseURL(redisURL)
	}
	return &redis.Options{Addr: redisURL}, nil
}
