# Configuration Guide

## Overview

The MP3 Handler uses Spring Boot's configuration system with support for multiple environments and external configuration sources.

## Configuration Files

### 1. application.yml (Base Configuration)
Default values that work for all environments.

```yaml
objectstore:
  endpoint: ${OBJECTSTORE_ENDPOINT:http://localhost:9000}
  accessKey: ${OBJECTSTORE_ACCESS_KEY:admin}
  secretKey: ${OBJECTSTORE_SECRET_KEY:admin123}
  bucket: ${OBJECTSTORE_BUCKET:audio-dev}
  region: ${OBJECTSTORE_REGION:us-east-1}
  pathStyleAccess: ${OBJECTSTORE_PATH_STYLE:true}

whisper:
  baseUrl: ${WHISPER_BASE_URL:http://localhost:8091}
  readTimeout: ${WHISPER_TIMEOUT_SECONDS:120}
  maxRetries: ${WHISPER_MAX_RETRIES:3}
```

### 2. application-local.yml (Local Development)
For running on your laptop:

```yaml
objectstore:
  endpoint: http://localhost:9000
  accessKey: minioadmin
  secretKey: minioadmin
  bucket: audio-local

whisper:
  baseUrl: http://localhost:8091
```

### 3. application-dev.yml (Development Environment)
For shared dev environment:

```yaml
objectstore:
  endpoint: https://minio-dev.yourcompany.com
  accessKey: ${MINIO_ACCESS_KEY}  # From environment variable
  secretKey: ${MINIO_SECRET_KEY}  # From environment variable
  bucket: audio-dev

whisper:
  baseUrl: https://whisper-dev.yourcompany.com
```

### 4. application-prod.yml (Production)
For production:

```yaml
objectstore:
  endpoint: https://s3.amazonaws.com
  accessKey: ${AWS_ACCESS_KEY_ID}
  secretKey: ${AWS_SECRET_ACCESS_KEY}
  bucket: audio-production
  region: us-east-1
  pathStyleAccess: false  # Use virtual-hosted style for AWS S3

whisper:
  baseUrl: https://whisper-prod.yourcompany.com
  readTimeout: 300
```

## Configuration Methods

### Method 1: Environment Variables (Recommended for Production)

Set environment variables before running:

```bash
# Linux/Mac
export OBJECTSTORE_ENDPOINT=https://minio.yourcompany.com
export OBJECTSTORE_ACCESS_KEY=your-access-key
export OBJECTSTORE_SECRET_KEY=your-secret-key
export OBJECTSTORE_BUCKET=audio-production
export WHISPER_BASE_URL=https://whisper.yourcompany.com

# Run application
java -jar mp3-handler.jar
```

```bash
# Windows
set OBJECTSTORE_ENDPOINT=https://minio.yourcompany.com
set OBJECTSTORE_ACCESS_KEY=your-access-key
set OBJECTSTORE_SECRET_KEY=your-secret-key
set OBJECTSTORE_BUCKET=audio-production
set WHISPER_BASE_URL=https://whisper.yourcompany.com

# Run application
java -jar mp3-handler.jar
```

### Method 2: Spring Profiles

Activate a specific profile:

```bash
# Use dev profile
java -jar mp3-handler.jar --spring.profiles.active=dev

# Use prod profile
java -jar mp3-handler.jar --spring.profiles.active=prod

# Use multiple profiles
java -jar mp3-handler.jar --spring.profiles.active=prod,monitoring
```

### Method 3: External Configuration File

Create a config file outside the JAR:

```bash
# Create application.yml in same directory as JAR
cat > application.yml <<EOF
objectstore:
  endpoint: https://minio.yourcompany.com
  accessKey: your-key
  secretKey: your-secret
  bucket: audio-prod
EOF

# Run (Spring Boot automatically picks it up)
java -jar mp3-handler.jar
```

### Method 4: Command Line Arguments

Override specific properties:

```bash
java -jar mp3-handler.jar \
  --objectstore.endpoint=https://minio.yourcompany.com \
  --objectstore.accessKey=your-key \
  --objectstore.secretKey=your-secret \
  --objectstore.bucket=audio-prod \
  --whisper.baseUrl=https://whisper.yourcompany.com
```

### Method 5: Docker Environment Variables

In docker-compose.yml:

```yaml
version: '3.8'
services:
  mp3-handler:
    image: mp3-handler:latest
    environment:
      - OBJECTSTORE_ENDPOINT=http://minio:9000
      - OBJECTSTORE_ACCESS_KEY=minioadmin
      - OBJECTSTORE_SECRET_KEY=minioadmin
      - OBJECTSTORE_BUCKET=audio-files
      - WHISPER_BASE_URL=http://whisper:8091
    ports:
      - "8080:8080"
```

Or using .env file:

```bash
# .env file
OBJECTSTORE_ENDPOINT=http://minio:9000
OBJECTSTORE_ACCESS_KEY=minioadmin
OBJECTSTORE_SECRET_KEY=minioadmin
OBJECTSTORE_BUCKET=audio-files
WHISPER_BASE_URL=http://whisper:8091
```

```yaml
# docker-compose.yml
version: '3.8'
services:
  mp3-handler:
    image: mp3-handler:latest
    env_file:
      - .env
    ports:
      - "8080:8080"
```

### Method 6: Kubernetes ConfigMap & Secrets

ConfigMap for non-sensitive data:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: mp3-handler-config
data:
  OBJECTSTORE_ENDPOINT: "https://minio.yourcompany.com"
  OBJECTSTORE_BUCKET: "audio-production"
  WHISPER_BASE_URL: "https://whisper.yourcompany.com"
```

Secret for sensitive data:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mp3-handler-secrets
type: Opaque
stringData:
  OBJECTSTORE_ACCESS_KEY: "your-access-key"
  OBJECTSTORE_SECRET_KEY: "your-secret-key"
```

Deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mp3-handler
spec:
  template:
    spec:
      containers:
      - name: mp3-handler
        image: mp3-handler:latest
        envFrom:
        - configMapRef:
            name: mp3-handler-config
        - secretRef:
            name: mp3-handler-secrets
```

## Configuration Priority (Highest to Lowest)

1. Command line arguments
2. Environment variables
3. External application.yml (in current directory)
4. Profile-specific files (application-{profile}.yml)
5. Internal application.yml (in JAR)

## Security Best Practices

### ❌ DON'T:
- Commit secrets to Git
- Use default passwords in production
- Store credentials in application.yml for production

### ✅ DO:
- Use environment variables for secrets
- Use secrets management (AWS Secrets Manager, HashiCorp Vault, etc.)
- Use different credentials per environment
- Rotate credentials regularly
- Use IAM roles when possible (AWS)

## Example Configurations

### Local Development (MinIO)

```yaml
# application-local.yml
objectstore:
  endpoint: http://localhost:9000
  accessKey: minioadmin
  secretKey: minioadmin
  bucket: audio-local
  pathStyleAccess: true

whisper:
  baseUrl: http://localhost:8091
```

Run:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### AWS S3 Production

```yaml
# application-prod.yml
objectstore:
  endpoint: https://s3.amazonaws.com
  accessKey: ${AWS_ACCESS_KEY_ID}
  secretKey: ${AWS_SECRET_ACCESS_KEY}
  bucket: my-company-audio-prod
  region: us-east-1
  pathStyleAccess: false

whisper:
  baseUrl: https://whisper-api.mycompany.com
  readTimeout: 300
```

Run:
```bash
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=secret...
java -jar mp3-handler.jar --spring.profiles.active=prod
```

### Docker Compose Full Stack

```yaml
version: '3.8'

services:
  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin123
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data

  whisper:
    image: your-whisper-image:latest
    ports:
      - "8091:8091"

  mp3-handler:
    image: mp3-handler:latest
    environment:
      OBJECTSTORE_ENDPOINT: http://minio:9000
      OBJECTSTORE_ACCESS_KEY: minioadmin
      OBJECTSTORE_SECRET_KEY: minioadmin123
      OBJECTSTORE_BUCKET: audio-files
      WHISPER_BASE_URL: http://whisper:8091
    ports:
      - "8080:8080"
    depends_on:
      - minio
      - whisper

volumes:
  minio-data:
```

## Verifying Configuration

Check active configuration:

```bash
# View all properties
curl http://localhost:8080/actuator/configprops

# View environment
curl http://localhost:8080/actuator/env

# Health check
curl http://localhost:8080/actuator/health
```

## Troubleshooting

### Issue: Can't connect to MinIO

**Check:**
```bash
# Test MinIO connectivity
curl http://localhost:9000/minio/health/live

# Check environment variables
echo $OBJECTSTORE_ENDPOINT
```

### Issue: Wrong bucket

**Check:**
```bash
# View active configuration
curl http://localhost:8080/actuator/env | grep objectstore.bucket
```

### Issue: Credentials not working

**Check:**
```bash
# Test MinIO credentials
mc alias set local http://localhost:9000 minioadmin minioadmin123
mc ls local
```

## Quick Start Commands

### Local Development
```bash
# Start MinIO
docker run -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin123 \
  minio/minio server /data --console-address ":9001"

# Run application
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Production
```bash
# Set environment variables
export OBJECTSTORE_ENDPOINT=https://s3.amazonaws.com
export OBJECTSTORE_ACCESS_KEY=$AWS_ACCESS_KEY_ID
export OBJECTSTORE_SECRET_KEY=$AWS_SECRET_ACCESS_KEY
export OBJECTSTORE_BUCKET=audio-production
export WHISPER_BASE_URL=https://whisper.mycompany.com

# Run
java -jar mp3-handler.jar --spring.profiles.active=prod
```

## Summary

**For Local Development:**
- Use `application-local.yml` with hardcoded localhost values
- Run with `-Dspring-boot.run.profiles=local`

**For Shared Environments (Dev/Staging):**
- Use environment variables for credentials
- Use `application-dev.yml` for non-sensitive config
- Activate with `--spring.profiles.active=dev`

**For Production:**
- Use environment variables for ALL sensitive data
- Use secrets management system
- Use `application-prod.yml` for non-sensitive config
- Activate with `--spring.profiles.active=prod`
