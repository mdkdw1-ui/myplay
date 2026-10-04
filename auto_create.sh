#!/bin/bash

# 환경변수 및 ID 자동 로드
COMPARTMENT_ID=$(grep tenancy ~/.oci/config | head -n1 | cut -d= -f2 | tr -d ' ')
AD_NAME=$(oci iam availability-domain list --region ap-tokyo-1 --compartment-id "$COMPARTMENT_ID" --query "data[0].name" --raw-output)
SUBNET_ID=$(oci network subnet list --region ap-tokyo-1 --compartment-id "$COMPARTMENT_ID" --query "data[0].id" --raw-output)

# Canonical Ubuntu 24.04 Minimal aarch64 이미지 ID 추출
IMAGE_ID=$(oci compute image list --region ap-tokyo-1 --compartment-id "$COMPARTMENT_ID" --shape "VM.Standard.A1.Flex" --query "data[?contains(\"display-name\", 'Canonical Ubuntu') && contains(\"display-name\", '24.04') && contains(\"display-name\", 'Minimal')].id | [0]" --raw-output)

SSH_KEY_PATH="$HOME/.ssh/id_rsa.pub"

export SUPPRESS_LABEL_WARNING=True

echo "=========================================="
echo "OCI Ampere ARM Instance Auto Provisioner"
echo "Region: ap-tokyo-1"
echo "OS: Ubuntu 24.04 Minimal (aarch64)"
echo "Shape: VM.Standard.A1.Flex (4 OCPU / 24GB RAM)"
echo "=========================================="

TRY_COUNT=1

while true; do
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [Attempt $TRY_COUNT] Requesting instance creation..."

    RESPONSE=$(oci compute instance launch \
        --compartment-id "$COMPARTMENT_ID" \
        --availability-domain "$AD_NAME" \
        --shape "VM.Standard.A1.Flex" \
        --shape-config '{"ocpus": 2, "memory_in_gbs": 12}' \
        --image-id "$IMAGE_ID" \
        --subnet-id "$SUBNET_ID" \
        --assign-public-ip true \
        --display-name "ARM-Ubuntu-24.04-Minimal" \
        --ssh-authorized-keys-file "$SSH_KEY_PATH" \
        --region ap-tokyo-1 2>&1)

    if echo "$RESPONSE" | grep -q '"lifecycle-state": "PROVISIONING"' || echo "$RESPONSE" | grep -q '"id":'; then
        echo "--------------------------------------------------"
        echo "🎉 SUCCESS! Instance creation started!"
        echo "--------------------------------------------------"
        echo "$RESPONSE"
        break
    else
        echo "❌ Failed (Out of capacity or rate limit). Retrying in 30 seconds..."
        sleep 30
    fi
    TRY_COUNT=$((TRY_COUNT + 1))
done
