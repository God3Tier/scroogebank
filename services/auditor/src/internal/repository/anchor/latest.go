package anchor

import (
	"context"
	"encoding/json"

	"auditor/src/internal/models"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) Latest(ctx context.Context) (*models.ChainAnchor, error) {
	// S3 lists keys in ascending order, and keys sort in the order they were
	// written, so the last key listed is the newest anchor.
	var newest string
	pages := s3.NewListObjectsV2Paginator(i.s3, &s3.ListObjectsV2Input{
		Bucket: aws.String(i.bucket),
		Prefix: aws.String(keyPrefix),
	})
	for pages.HasMorePages() {
		page, err := pages.NextPage(ctx)
		if err != nil {
			return nil, pkgerrors.WithStack(err)
		}
		if n := len(page.Contents); n > 0 {
			newest = aws.ToString(page.Contents[n-1].Key)
		}
	}
	if newest == "" {
		return nil, nil
	}

	object, err := i.s3.GetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(i.bucket),
		Key:    aws.String(newest),
	})
	if err != nil {
		return nil, pkgerrors.WithStack(err)
	}
	defer object.Body.Close()

	var document anchorDocument
	if err := json.NewDecoder(object.Body).Decode(&document); err != nil {
		return nil, pkgerrors.Wrapf(err, "read anchor %s", newest)
	}
	anchor := document.model()
	if anchor.SequenceNo < 1 || anchor.ID == "" || anchor.Hash == "" {
		return nil, pkgerrors.Errorf("anchor %s is incomplete", newest)
	}
	return &anchor, nil
}
