# The Cognito triggers (ADR-0040): a Java 25 Lambda built with Maven, like the
# other Java services. make test needs JDK 25, Maven and Docker (Testcontainers
# runs LocalStack for the SQS tests). make invoke and make replay run the built
# zip in the pinned Corretto image, the JDK of Lambda's java25 runtime.
MVN ?= mvn
MVN_ARGS ?= -e
JAVA_IMAGE := amazoncorretto:25.0.4-al2023
ZIP := target/cognito-triggers.zip
RUN_ZIP = docker run --rm --add-host=host.docker.internal:host-gateway -v "$(CURDIR)":/workspace -w /workspace
UNPACK := mkdir /tmp/task && cd /tmp/task && jar xf /workspace/$(ZIP) && cd /workspace
CLASSPATH := /tmp/task:/tmp/task/lib/*

# make invoke sends to the auditor's local queue (make run in services/auditor).
# LocalStack checks no credentials, so the key is a placeholder, not a secret.
LOCAL_ENDPOINT := http://host.docker.internal:4566
LOCALSTACK_KEY := test
EVENT ?= post-authentication

# make replay: the audit-insert queue's URL, and the saved filter-log-events output.
QUEUE_URL ?=
LOG_FILE ?= failed.json

.PHONY: test package invoke replay clean

## Run the unit and LocalStack tests, then build the Lambda zip
test:
	$(MVN) $(MVN_ARGS) -f pom.xml verify

## Build target/cognito-triggers.zip without running the tests
package:
	$(MVN) $(MVN_ARGS) -f pom.xml package -DskipTests

## Run the zip's handler once on src/test/resources/events/$(EVENT).json against
## the auditor's local queue, for example make invoke EVENT=confirm-forgot-password
invoke:
	$(RUN_ZIP) -e AWS_REGION=us-east-1 -e AWS_ACCESS_KEY_ID=$(LOCALSTACK_KEY) -e AWS_SECRET_ACCESS_KEY=$(LOCALSTACK_KEY) \
		-e AWS_ENDPOINT_URL=$(LOCAL_ENDPOINT) -e AUDIT_QUEUE_URL=$(LOCAL_ENDPOINT)/000000000000/audit-insert \
		$(JAVA_IMAGE) sh -c '$(UNPACK) && java -cp "$(CLASSPATH)" bank.cognito.Invoke src/test/resources/events/$(EVENT).json'

## Send the events in $(LOG_FILE) to $(QUEUE_URL) (see Replaying failed events
## in the README), with the replay role's credentials in the environment
replay:
	@test -n "$(QUEUE_URL)" || (echo "Set QUEUE_URL to the audit-insert queue URL"; exit 1)
	$(RUN_ZIP) -e AWS_REGION -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
		$(JAVA_IMAGE) sh -c '$(UNPACK) && java -cp "$(CLASSPATH)" bank.cognito.Replay --queue-url "$(QUEUE_URL)" "$(LOG_FILE)"'

## Remove Maven build outputs
clean:
	$(MVN) $(MVN_ARGS) -f pom.xml clean
