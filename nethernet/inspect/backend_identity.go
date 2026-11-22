package main

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/go-jose/go-jose/v4"
	"github.com/go-jose/go-jose/v4/jwt"
	"github.com/helix-bedrock/nethernet"
)

const (
	backendKeyID    = "navro-proxy"
	backendAudience = "urn:navro:nethernet-backend"
)

func loadBackendSigningKey(path string) (*rsa.PrivateKey, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("expected an RSA private key in PEM format")
	}
	var parsed any
	switch block.Type {
	case "PRIVATE KEY":
		parsed, err = x509.ParsePKCS8PrivateKey(block.Bytes)
	case "RSA PRIVATE KEY":
		parsed, err = x509.ParsePKCS1PrivateKey(block.Bytes)
	default:
		return nil, errors.New("expected a PKCS#8 or PKCS#1 RSA private key")
	}
	if err != nil {
		return nil, err
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok || key.N.BitLen() < 2048 {
		return nil, errors.New("backend signing requires an RSA key of at least 2048 bits")
	}
	return key, key.Validate()
}

func signedBackendIdentity(sessionKey *ecdsa.PrivateKey, key *rsa.PrivateKey) (*nethernet.Identity, error) {
	publicDER, err := x509.MarshalPKIXPublicKey(&sessionKey.PublicKey)
	if err != nil {
		return nil, err
	}
	options := new(jose.SignerOptions).WithHeader("kid", backendKeyID)
	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.RS256, Key: key}, options)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	token, err := jwt.Signed(signer).Claims(jwt.Claims{
		Issuer: backendKeyID, Subject: backendKeyID, Audience: jwt.Audience{backendAudience},
		IssuedAt: jwt.NewNumericDate(now), Expiry: jwt.NewNumericDate(now.Add(time.Minute)),
	}).Claims(struct {
		PublicKey string `json:"cpk"`
	}{PublicKey: base64.StdEncoding.EncodeToString(publicDER)}).Serialize()
	if err != nil {
		return nil, err
	}
	return &nethernet.Identity{PrivateKey: sessionKey, Token: token, Domain: backendKeyID}, nil
}

func backendPublicKeyPath(privatePath string) string {
	return strings.TrimSuffix(privatePath, ".pem") + ".pub.pem"
}

func initBackendSigningKey(privatePath string) error {
	if err := os.MkdirAll(filepath.Dir(privatePath), 0700); err != nil {
		return err
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	privateDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	publicDER, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return err
	}
	publicPath := backendPublicKeyPath(privatePath)
	if err := writeNewPEM(privatePath, "PRIVATE KEY", privateDER, 0600); err != nil {
		return err
	}
	if err := writeNewPEM(publicPath, "PUBLIC KEY", publicDER, 0644); err != nil {
		return errors.Join(err, os.Remove(privatePath))
	}
	return nil
}

func writeNewPEM(path, kind string, der []byte, mode os.FileMode) error {
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	err = errors.Join(pem.Encode(file, &pem.Block{Type: kind, Bytes: der}), file.Close())
	if err != nil {
		return errors.Join(err, os.Remove(path))
	}
	return nil
}
