#!/bin/bash

export SUPPRESS_LABEL_WARNING=True

COMPARTMENT_ID=$(grep tenancy ~/.oci/config | head -n1 | cut -d= -f2 | tr -d ' ')
AD_NAME=$(oci iam availability-domain list --region ap-tokyo-1 --compartment-id "$COMPARTMENT_ID" --query "data[0].name" --raw-output)
SUBNET_ID=$(oci network subnet list --region ap-tokyo-1 --compartment-id "$COMPARTMENT_ID" --query "data[0].id" --raw-output)
IMAGE_ID=$(oci compute image list --region ap-tokyo-1 --compartment-id "$COMPARTMENT_ID" --shape "VM.Standard.A1.Flex" --query "data[?contains(\"display-name\", '24.04') && contains(\"display-name\", 'Minimal')].id | [0]" --raw-output)

SSH_KEY_PATH="$HOME/.ssh/id_rsa.pub"

if [ -z "$IMAGE_ID" ] || [ "$IMAGE_ID" == "null" ]; then
    echo "❌ 에러: IMAGE_ID를 찾지 못했습니다."
    exit 1
fi

echo "=========================================="
echo "OCI Ampere ARM Instance Auto Provisioner"
echo "Region: ap-tokyo-1"
echo "Shape: VM.Standard.A1.Flex (2 OCPU / 12GB RAM)"
echo "Image ID: $IMAGE_ID"
echo "Interval: 30s"
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
    elif echo "$RESPONSE" | grep -q "TooManyRequests"; then
        echo "⚠️ Rate limit hit (TooManyRequests). Cooling down for 120 seconds..."
        sleep 120
    else
        ERR_MSG=$(echo "$RESPONSE" | grep '"message":' | head -n1 | cut -d'"' -f4)
        [ -z "$ERR_MSG" ] && ERR_MSG="Out of capacity or unknown error"
        echo "❌ Failed ($ERR_MSG). Retrying in 30 seconds..."
        sleep 30
    fi
    TRY_COUNT=$((TRY_COUNT + 1))
done
