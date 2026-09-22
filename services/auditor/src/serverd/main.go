package serverd

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"auditor/src/gen"
	"auditor/src/internal/controller"
	"auditor/src/internal/repository"
	"auditor/src/internal/service"
	"auditor/src/serverd/kafka"

	"google.golang.org/grpc"
)

const defaultPort = 8080

func main() {
	ctx := context.Background()
	if err := run(ctx); err != nil {
		log.Fatal(err)
	}
}

func run(ctx context.Context) error {
	// Setup db and dependencies
	db, err := IntialiseDB()
	if err != nil {
		return err
	}

	repository := repository.New(db)
	service := service.New(repository.GetAudit())
	controller := controller.New(service)

	// Set up wait group for goroutines
	var wg sync.WaitGroup

	wg.Add(4)

	// Set up grpc server
	grpcPort := os.Getenv("GRPC_PORT")
	lis, err := net.Listen("tcp", grpcPort)
	if err != nil {
		return fmt.Errorf("failed to listen: %v", err)
	}

	grpcServer := grpc.NewServer()
	gen.RegisterAuditServiceServer(grpcServer, controller)

	go func() {
		defer wg.Done()
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatalf("failed to serve: %v", err)
		}
	}()

	// Intiialise kafka listener
	rejectionQueue := make(chan kafka.Rejection, 100)
	kafkaBrokers := strings.Split(os.Getenv("KAFKABROKERS"), ",")
	kafkaReader := kafka.InitKafkaReader("audit.insert", kafkaBrokers)
	defer kafkaReader.Close()
	kafkaWriter := kafka.InitKafkaWriter("dql.audit", os.Getenv("HANDLER_IP"))
	defer kafkaWriter.Close()

	go func() {
		defer wg.Done()
		kafka.RunKafkaReader(ctx, kafkaReader, repository, rejectionQueue)
	}()

	go func() {
		defer wg.Done()
		kafka.RunKafkaWriter(ctx, kafkaWriter, rejectionQueue)
	}()

	// Setup HTTP health server
	port := getHTTPPort()
	server := &http.Server{
		Addr:              ":" + strconv.Itoa(port),
		Handler:           newHandler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	shutdownContext, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		defer wg.Done()
		<-shutdownContext.Done()
		shutdownDeadline, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()

		if err := server.Shutdown(shutdownDeadline); err != nil {
			log.Printf("server shutdown failed: %v", err)
		}
	}()

	log.Printf("auditor listening on http://localhost:%d", port)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return fmt.Errorf("server failed: %v", err)
	}

	close(rejectionQueue)

	wg.Wait()
	return nil
}

func IntialiseDB() (*sql.DB, error) {
	dbname := os.Getenv("DB_NAME")
	host := os.Getenv("HOST")
	user := os.Getenv("USER")
	password := os.Getenv("PASSWORD")
	sslmode := os.Getenv("SSLMODE")

	return sql.Open("postgres", fmt.Sprintf("dbname=%s host=%s user=%s password=%s sslmode=%s", dbname, host, user, password, sslmode))
}

/*
 * Sure
 */
func getHTTPPort() int {
	port, err := strconv.Atoi(os.Getenv("HTTP_PORT"))
	if err != nil || port < 1 || port > 65535 {
		return defaultPort
	}
	return port
}
