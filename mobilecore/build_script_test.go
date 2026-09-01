package mobilecore

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestAARCacheKeyCoversToolVersionsAndPatch(t *testing.T) {
	base := aarCacheKey(t, nil)
	for _, variable := range []string{
		"MOBILECORE_MOBILE_COMMIT",
		"MOBILECORE_TOOLS_VERSION",
		"MOBILECORE_MOD_VERSION",
		"MOBILECORE_SYNC_VERSION",
	} {
		t.Run(variable, func(t *testing.T) {
			if got := aarCacheKey(t, []string{variable + "=changed"}); got == base {
				t.Fatalf("cache key did not change with %s", variable)
			}
		})
	}

	patch, err := os.ReadFile("patches/gomobile-local-module.patch")
	if err != nil {
		t.Fatal(err)
	}
	modified := filepath.Join(t.TempDir(), "gomobile.patch")
	if err := os.WriteFile(modified, append(patch, []byte("\n# cache-key-test\n")...), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := aarCacheKey(t, []string{"MOBILECORE_GOMOBILE_PATCH=" + modified}); got == base {
		t.Fatal("cache key did not change with patch contents")
	}
}

func aarCacheKey(t *testing.T, extraEnv []string) string {
	t.Helper()
	cmd := exec.Command("bash", "./build-aar.sh", "--print-cache-key")
	cmd.Env = append(os.Environ(), extraEnv...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("print cache key: %v: %s", err, out)
	}
	key := strings.TrimSpace(string(out))
	if len(key) != 64 {
		t.Fatalf("cache key %q has length %d", key, len(key))
	}
	return key
}
