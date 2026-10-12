package anchor

import (
	"bytes"
	"context"
	"encoding/json"

	"auditor/src/internal/models"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) Write(ctx context.Context, anchor models.ChainAnchor) error {
	body, err := json.Marshal(anchor)
	if err != nil {
		return pkgerrors.WithStack(err)
	}

	_, err = i.s3.PutObject(ctx, &s3.PutObjectInput{
		Bucket:      aws.String(i.bucket),
		Key:         aws.String(key(anchor.AnchoredAt)),
		Body:        bytes.NewReader(body),
		ContentType: aws.String("application/json"),
		// The lock is set on each object rather than left to the bucket's
		// default, so S3 refuses the write on a bucket without Object Lock
		// and the run fails, instead of leaving an anchor unlocked.
		ObjectLockMode:            types.ObjectLockModeGovernance,
		ObjectLockRetainUntilDate: aws.Time(anchor.AnchoredAt.Add(Retention)),
		// S3 requires a checksum on a write that sets a lock.
		ChecksumAlgorithm: types.ChecksumAlgorithmCrc32,
	})
	return pkgerrors.WithStack(err)
}
