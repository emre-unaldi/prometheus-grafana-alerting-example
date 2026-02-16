#!/bin/bash

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

ORDER_SERVICE="http://localhost:8081"
INVENTORY_SERVICE="http://localhost:8082"
PAYMENT_SERVICE="http://localhost:8083"
PROMETHEUS="http://localhost:9090"
GRAFANA="http://localhost:3000"

print_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

print_success() {
    echo -e "${GREEN}$1${NC}"
}

print_warning() {
    echo -e "${YELLOW}$1${NC}"
}

print_error() {
    echo -e "${RED}$1${NC}"
}

print_info() {
    echo -e "${BLUE}$1${NC}"
}

wait_seconds() {
    local seconds=$1
    local message=$2
    echo -n "$message"
    for i in $(seq $seconds -1 1); do
        echo -n " $i"
        sleep 1
    done
    echo ""
}

check_service() {
    local service_name=$1
    local service_url=$2

    if curl -s -f "${service_url}/actuator/health" > /dev/null 2>&1; then
        print_success "$service_name is running"
        return 0
    else
        print_error "$service_name is not running"
        return 1
    fi
}

check_h2_console() {
    local service_name=$1
    local service_url=$2

    if curl -s -f "${service_url}/h2-console" > /dev/null 2>&1; then
        print_success "$service_name H2 Console is accessible"
        return 0
    else
        print_warning "$service_name H2 Console is not accessible"
        return 0
    fi
}

print_header "MICROSERVICES MONITORING - ALERT TEST SCRIPT"

print_info "Checking services..."
check_service "Order Service" "$ORDER_SERVICE" || exit 1
check_service "Inventory Service" "$INVENTORY_SERVICE" || exit 1
check_service "Payment Service" "$PAYMENT_SERVICE" || exit 1

echo ""
print_info "Checking H2 Consoles..."
check_h2_console "Order Service" "$ORDER_SERVICE"
check_h2_console "Inventory Service" "$INVENTORY_SERVICE"
check_h2_console "Payment Service" "$PAYMENT_SERVICE"

echo ""
print_success "All services are running!"
echo ""

print_header "TEST 1: Normal Order Flow (Baseline)"
print_info "Creating 10 normal orders to establish baseline metrics..."

for i in {1..10}; do
    response=$(curl -s -X POST $ORDER_SERVICE/api/orders \
        -H "Content-Type: application/json" \
        -d "{
            \"customerId\": \"CUST-TEST-001\",
            \"productId\": \"PROD-001\",
            \"quantity\": 1,
            \"totalAmount\": 100.0
        }")
    echo -n "."
done
echo ""
print_success "Baseline orders created"
wait_seconds 5 "Waiting for metrics to be collected..."

print_header "TEST 2: High Payment Failure Rate Alert (CRITICAL)"
print_info "Scenario: Payment failure rate > 5%"
print_info "Expected Alert: HighPaymentFailureRate"

print_info "Setting payment failure rate to 20%..."
curl -s -X PUT $PAYMENT_SERVICE/api/payment/config/failure-rate \
    -H "Content-Type: application/json" \
    -d '{"rate": 0.20}' > /dev/null

print_success "Failure rate updated"

print_info "Creating 50 orders with high failure rate..."
for i in {1..50}; do
    curl -s -X POST $ORDER_SERVICE/api/orders \
        -H "Content-Type: application/json" \
        -d "{
            \"customerId\": \"CUST-TEST-002\",
            \"productId\": \"PROD-002\",
            \"quantity\": 1,
            \"totalAmount\": 150.0
        }" > /dev/null
    echo -n "."
    sleep 0.1
done
echo ""

print_warning "Alert should trigger in ~2 minutes"
print_info "Check Prometheus: $PROMETHEUS/alerts"
print_info "Check AlertManager: http://localhost:9093/#/alerts"

stats=$(curl -s $PAYMENT_SERVICE/api/payment/statistics)
echo ""
print_info "Payment Statistics:"
echo "$stats" | jq '.'

wait_seconds 10 "Waiting before next test..."

print_info "Resetting payment failure rate to 5%..."
curl -s -X PUT $PAYMENT_SERVICE/api/payment/config/failure-rate \
    -H "Content-Type: application/json" \
    -d '{"rate": 0.05}' > /dev/null

print_header "TEST 3: Critical Stock Level Alert (WARNING)"
print_info "Scenario: Product stock < 10 units"
print_info "Expected Alert: CriticalStockLevel"

print_info "Current critical stock products:"
critical_products=$(curl -s $INVENTORY_SERVICE/api/inventory/critical)
echo "$critical_products" | jq '.'

print_info "Decreasing stock for PROD-006..."
curl -s -X POST $INVENTORY_SERVICE/api/inventory/PROD-006/decrease \
    -H "Content-Type: application/json" \
    -d '{"quantity": 2}' > /dev/null

print_success "Stock decreased"

print_info "Updated inventory for PROD-006:"
curl -s $INVENTORY_SERVICE/api/inventory/PROD-006 | jq '.'

print_warning "Alert should trigger in ~1 minute"

wait_seconds 10 "Waiting before next test..."

print_header "TEST 4: Slow Response Time Alert (WARNING)"
print_info "Scenario: p95 response time > 2 seconds"
print_info "Expected Alert: SlowOrderServiceResponseTime"

print_info "Creating high load with 100 concurrent orders..."

for i in {1..100}; do
    curl -s -X POST $ORDER_SERVICE/api/orders \
        -H "Content-Type: application/json" \
        -d "{
            \"customerId\": \"CUST-LOAD-TEST\",
            \"productId\": \"PROD-003\",
            \"quantity\": $((RANDOM % 5 + 1)),
            \"totalAmount\": $((RANDOM % 500 + 100)).0
        }" > /dev/null &
done

print_success "Load test started (100 concurrent requests)"
print_warning "Alert might trigger in ~3 minutes if response time degrades"

wait_seconds 10 "Waiting for requests to complete..."

print_header "TEST 5: High Order Failure Rate Alert (CRITICAL)"
print_info "Scenario: Order failure rate > 10%"
print_info "Expected Alert: HighOrderFailureRate"

print_info "Setting payment failure rate to 30% temporarily..."
curl -s -X PUT $PAYMENT_SERVICE/api/payment/config/failure-rate \
    -H "Content-Type: application/json" \
    -d '{"rate": 0.30}' > /dev/null

print_info "Creating 40 orders with high failure..."
for i in {1..40}; do
    curl -s -X POST $ORDER_SERVICE/api/orders \
        -H "Content-Type: application/json" \
        -d "{
            \"customerId\": \"CUST-FAIL-TEST\",
            \"productId\": \"PROD-004\",
            \"quantity\": 1,
            \"totalAmount\": 200.0
        }" > /dev/null
    echo -n "."
    sleep 0.1
done
echo ""

print_warning "Alert should trigger in ~3 minutes"

curl -s -X PUT $PAYMENT_SERVICE/api/payment/config/failure-rate \
    -H "Content-Type: application/json" \
    -d '{"rate": 0.05}' > /dev/null

wait_seconds 10 "Waiting before summary..."

print_header "TEST 6: No Activity Alerts (Optional)"
print_info "Scenario: No orders/payments for 5 minutes"
print_info "Expected Alerts: NoOrderActivity, NoPaymentActivity"
print_warning "Skipping this test as it requires 5 minutes of inactivity"
print_info "To test: Stop creating orders for 5+ minutes"

print_header "TEST SUMMARY"

echo ""
print_info "All test scenarios have been executed!"
echo ""
print_info "Next Steps:"
echo "  1. Check Prometheus Alerts: $PROMETHEUS/alerts"
echo "  2. Check AlertManager: http://localhost:9093/#/alerts"
echo "  3. Check Grafana Dashboard: $GRAFANA"
echo "  4. Wait 2-3 minutes for alerts to trigger"
echo ""

print_info "Expected Alerts (in order of trigger time):"
echo "  - CriticalStockLevel (1 min)"
echo "  - HighPaymentFailureRate (2 min)"
echo "  - HighOrderFailureRate (3 min)"
echo "  - SlowOrderServiceResponseTime (3 min - if load was high enough)"
echo ""

print_info "Notifications:"
echo "  - Check your email inbox (if SMTP configured)"
echo ""

print_info "H2 Database Consoles:"
echo "  - Order Service: http://localhost:8081/h2-console (JDBC URL: jdbc:h2:mem:orderdb)"
echo "  - Inventory Service: http://localhost:8082/h2-console (JDBC URL: jdbc:h2:mem:inventorydb)"
echo "  - Payment Service: http://localhost:8083/h2-console (JDBC URL: jdbc:h2:mem:paymentdb)"
echo ""

print_info "Additional Manual Tests:"
echo "  - Service Down: docker-compose stop payment-service"
echo "  - High CPU: Generate sustained high load"
echo "  - High Memory: Create memory leak scenario"
echo ""

print_success "Test script completed successfully!"
echo ""

print_header "USEFUL COMMANDS"

cat << EOF
curl -s $PROMETHEUS/api/v1/alerts | jq '.data.alerts'

curl -s "$PROMETHEUS/api/v1/query?query=rate(payment_failed_total[5m])" | jq '.data.result'

curl -s $PAYMENT_SERVICE/api/payment/statistics | jq '.'

curl -s $INVENTORY_SERVICE/api/inventory/critical | jq '.'

docker-compose logs -f order-service
docker-compose logs -f payment-service
docker-compose logs -f inventory-service
docker-compose logs -f prometheus
docker-compose logs -f alertmanager

docker-compose restart payment-service

docker-compose stop payment-service
sleep 70
docker-compose start payment-service
EOF

echo ""
