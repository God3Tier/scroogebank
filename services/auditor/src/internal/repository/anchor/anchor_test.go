package anchor

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"auditor/src/internal/models"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeS3 keeps objects in memory and lists them pageSize keys at a time, in
// ascending order, as S3 does.
type fakeS3 struct {
	objects  map[string][]byte
	pageSize int
	puts     []*s3.PutObjectInput
	listErr  error
}

func newFakeS3(pageSize int) *fakeS3 {
	return &fakeS3{objects: map[string][]byte{}, pageSize: pageSize}
}

func (f *fakeS3) PutObject(_ context.Context, in *s3.PutObjectInput, _ ...func(*s3.Options)) (*s3.PutObjectOutput, error) {
	body, err := io.ReadAll(in.Body)
	if err != nil {
		return nil, err
	}
	f.objects[aws.ToString(in.Key)] = body
	f.puts = append(f.puts, in)
	return &s3.PutObjectOutput{}, nil
}

func (f *fakeS3) GetObject(_ context.Context, in *s3.GetObjectInput, _ ...func(*s3.Options)) (*s3.GetObjectOutput, error) {
	body, ok := f.objects[aws.ToString(in.Key)]
	if !ok {
		return nil, &types.NoSuchKey{}
	}
	return &s3.GetObjectOutput{Body: io.NopCloser(bytes.NewReader(body))}, nil
}

func (f *fakeS3) ListObjectsV2(_ context.Context, in *s3.ListObjectsV2Input, _ ...func(*s3.Options)) (*s3.ListObjectsV2Output, error) {
	if f.listErr != nil {
		return nil, f.listErr
	}
	var keys []string
	for key := range f.objects {
		if strings.HasPrefix(key, aws.ToString(in.Prefix)) {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)

	start := 0
	if in.ContinuationToken != nil {
		start, _ = strconv.Atoi(*in.ContinuationToken)
	}
	end := min(start+f.pageSize, len(keys))
	out := &s3.ListObjectsV2Output{IsTruncated: aws.Bool(end < len(keys))}
	for _, key := range keys[start:end] {
		out.Contents = append(out.Contents, types.Object{Key: aws.String(key)})
	}
	if end < len(keys) {
		out.NextContinuationToken = aws.String(strconv.Itoa(end))
	}
	return out, nil
}

func anchorAt(sequence int64, at time.Time) models.ChainAnchor {
	return models.ChainAnchor{
		SequenceNo: sequence,
		ID:         "00000000-0000-4000-8000-00000000000" + strconv.FormatInt(sequence, 10),
		Hash:       "hash-" + strconv.FormatInt(sequence, 10),
		AnchoredAt: at,
	}
}

func TestWriteLocksEachAnchor(t *testing.T) {
	client := newFakeS3(10)
	anchoredAt := time.Date(2026, 10, 12, 15, 4, 5, 123456000, time.UTC)
	anchor := anchorAt(7, anchoredAt)

	require.NoError(t, New(client, "anchors").Write(context.Background(), anchor))

	require.Len(t, client.puts, 1)
	put := client.puts[0]
	assert.Equal(t, "anchors", aws.ToString(put.Bucket))
	assert.Equal(t, "chain-head/20261012T150405.123456Z.json", aws.ToString(put.Key))
	assert.Equal(t, types.ObjectLockModeGovernance, put.ObjectLockMode)
	assert.Equal(t, anchoredAt.Add(7*24*time.Hour), aws.ToTime(put.ObjectLockRetainUntilDate))
	assert.Equal(t, types.ChecksumAlgorithmCrc32, put.ChecksumAlgorithm)
	var stored models.ChainAnchor
	require.NoError(t, json.Unmarshal(client.objects[aws.ToString(put.Key)], &stored))
	assert.Equal(t, anchor, stored)
}

func TestLatest(t *testing.T) {
	first := time.Date(2026, 10, 10, 0, 0, 0, 0, time.UTC)

	t.Run("returns nil before any anchor", func(t *testing.T) {
		latest, err := New(newFakeS3(2), "anchors").Latest(context.Background())
		require.NoError(t, err)
		assert.Nil(t, latest)
	})

	t.Run("returns the anchor written last, across pages", func(t *testing.T) {
		client := newFakeS3(2)
		repository := New(client, "anchors")
		// Written days apart, not in sequence order: the newest write wins.
		for day, sequence := range []int64{5, 9, 7} {
			require.NoError(t, repository.Write(context.Background(), anchorAt(sequence, first.AddDate(0, 0, day))))
		}
		client.objects["other/ignored.json"] = []byte(`{}`)

		latest, err := repository.Latest(context.Background())

		require.NoError(t, err)
		require.NotNil(t, latest)
		assert.Equal(t, anchorAt(7, first.AddDate(0, 0, 2)), *latest)
	})

	t.Run("rejects an unreadable or incomplete anchor", func(t *testing.T) {
		for _, body := range []string{`not json`, `{"sequence_no":3,"id":"","hash":"h"}`} {
			client := newFakeS3(2)
			client.objects[key(first)] = []byte(body)

			_, err := New(client, "anchors").Latest(context.Background())

			assert.Error(t, err, body)
		}
	})

	t.Run("returns listing errors", func(t *testing.T) {
		client := newFakeS3(2)
		client.listErr = errors.New("access denied")

		_, err := New(client, "anchors").Latest(context.Background())

		assert.ErrorIs(t, err, client.listErr)
	})
}
