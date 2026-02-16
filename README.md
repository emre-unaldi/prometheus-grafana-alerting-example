# Prometheus + Grafana + AlertManager ile Mikroservis Monitoring

## İçindekiler

1. [Giriş ve Mimari Genel Bakış](#1-giris-ve-mimari-genel-bakis)
2. [Prometheus Nedir, Nasıl Kurulur](#2-prometheus-nedir-nasil-kurulur)
3. [Spring Boot Actuator ve Micrometer Entegrasyonu](#3-spring-boot-actuator-ve-micrometer-entegrasyonu)
4. [Default Metrikler - Tam Referans](#4-default-metrikler---tam-referans)
5. [Grafana Kurulumu ve Dashboard İmplementasyonu](#5-grafana-kurulumu-ve-dashboard-implementasyonu)
6. [Alert Sistemi](#6-alert-sistemi)
7. [Projeyi Ayağa Kaldırma (Hands-on)](#7-projeyi-ayaga-kaldirma-hands-on)
8. [PromQL Sorgu Örnekleri (Cheat Sheet)](#8-promql-sorgu-ornekleri-cheat-sheet)

---

## 1. Giriş ve Mimari Genel Bakış

### Projenin Amacı

Bu projede, örnek bir e-ticaret sisteminin 3 temel mikroservisini **sıfır custom metrik yazarak** izlemeyi hedefledim. Spring Boot Actuator ve Micrometer'in sunduğu default metrikler, çoğu production ortamı için fazlasıyla yeterli. Amacım, "metrik nedir, nasıl toplanır, nasıl görselleştirilir, nasıl alert alınır" sorularına cevap bulabilmektir.

### Mimari Diyagram

```
+-------------------+     +-------------------+     +-------------------+
|  Order Service    |     | Inventory Service |     | Payment Service   |
|  (Spring Boot)    |     | (Spring Boot)     |     | (Spring Boot)     |
|  Port: 8081       |     | Port: 8082        |     | Port: 8083        |
|                   |     |                   |     |                   |
| /actuator/        |     | /actuator/        |     | /actuator/        |
| prometheus        |     | prometheus        |     | prometheus        |
+--------+----------+     +--------+----------+     +--------+----------+
         |                         |                         |
         |    HTTP GET /actuator/prometheus (her 10s)        |
         +-------------------+-----+-------------------------+
                             |
                    +--------v-----------+
                    |    Prometheus      |
                    |    (v2.48.0)       |
                    |    Port: 9090      |
                    |                    |
                    | - Metrik toplama   |
                    | - PromQL engine    |
                    | - Alert engine     |
                    | - 15 gun retention |
                    +--------+----------+
                             |
                +------------+------------+
                |                         |
       +--------v-----------+    +---------v-----------+
       |    Grafana         |    |   AlertManager     |
       |    (v10.2.2)       |    |   (v0.26.0)        |
       |    Port: 3000      |    |   Port: 9093       |
       |                    |    |                    |
       | - 18 panel         |    | - Email routing    |
       | - Auto-provision   |    | - Severity bazli   |
       | - Dark theme       |    |                    |
       +--------------------+    +--------------------+
```

### Veri Akışı

Sistemin veri akışı şu şekilde çalışıyor:

1. **Servis** -- Spring Boot uygulaması çalışıyor, her HTTP isteğinde metrik üretiliyor
2. **Actuator + Micrometer** -- `/actuator/prometheus` endpoint'i, tüm metrikleri Prometheus formatında expose ediyor
3. **Prometheus** -- Her 10 saniyede bir (mikroservisler için) bu endpoint'e HTTP GET isteği atıyor ve metrikleri yazıyor
4. **Grafana** -- Prometheus'a PromQL sorguları göndererek metrikleri görselleşiriyor
5. **AlertManager** -- Prometheus alert kuralları tetiklendiğinde, AlertManager email üzerinden bildirim gönderiyor

> **Önemli:** Prometheus **pull-based** bir sistem. Yani servisler Prometheus'a metrik göndermez; Prometheus gider servisten metrikleri çeker. Bu tasarım, servislerin Prometheus'un varlığından haberdar olmasına gerek kalmadan çalışmasını sağlar.

---

## 2. Prometheus Nedir, Nasıl Kurulur

### Pull-Based Model

Prometheus, çoğu monitoring aracının aksine **pull-based** model kullanır. Bu ne demek?

- **Push-based** (örneğin Graphite, StatsD): Uygulamalar metrikleri monitoring sunucusuna gönderir.
- **Pull-based** (Prometheus): Prometheus, belirli aralıklarla hedef servislerin `/metrics` endpoint'ine HTTP GET isteği atarak metrikleri çeker.

Pull-based modelin avantajları:

- Servisler monitoring altyapısını bilmek zorunda değil
- Prometheus bir servisin ayakta olup olmadığını scrape başarısızlığından anlayabilir
- Service discovery ile yeni servisler otomatik eklenebilir
- Test ortamında Prometheus olmadan da servisler sorunsuz çalışır

### docker-compose.yml İçindeki Prometheus Konfigurasyonu

```yaml
prometheus:
  image: prom/prometheus:v2.48.0
  container_name: prometheus
  ports:
    - "9090:9090"
  networks:
    - ecommerce-network
  volumes:
    - ./monitoring/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
    - ./monitoring/prometheus/alert.rules.yml:/etc/prometheus/alert.rules.yml
    - prometheus-data:/prometheus
  command:
    - '--config.file=/etc/prometheus/prometheus.yml'
    - '--storage.tsdb.path=/prometheus'
    - '--storage.tsdb.retention.time=15d'
    - '--web.console.libraries=/usr/share/prometheus/console_libraries'
    - '--web.console.templates=/usr/share/prometheus/consoles'
    - '--web.enable-lifecycle'
  restart: unless-stopped
  depends_on:
    - order-service
    - inventory-service
    - payment-service
```

**Command flag'lerinin açıklaması:**

| Flag | Açıklama |
|------|----------|
| `--config.file` | Ana konfigürasyon dosyasının yolu |
| `--storage.tsdb.path` | Zaman serisi veritabanının (TSDB) yazılacağı dizin |
| `--storage.tsdb.retention.time=15d` | Metrikler 15 gün boyunca saklanır, sonra silinir |
| `--web.enable-lifecycle` | `/-/reload` endpoint'ini aktif eder; config değişikliklerini restart olmadan uygulayabilirsin |

### prometheus.yml Detaylı Açıklama

Bu dosya Prometheus'un tüm davranışını belirler. 4 ana bölümden oluşur:

#### 1. Global Bölümü

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    cluster: 'ecommerce-cluster'
    environment: 'development'
```

- **scrape_interval: 15s** -- Prometheus varsayılan olarak her 15 saniyede bir hedeflerden metrik toplar. Bireysel job'lar bu değeri override edebilir.
- **evaluation_interval: 15s** -- Alert kurallarını her 15 saniyede bir değerlendirir.
- **external_labels** -- Bu label'lar tüm metriklere otomatik eklenir. Federation veya remote write kullanırsanız, hangi cluster'dan geldiğini anlamak için kullanılır.

#### 2. Rule Files Bölümü

```yaml
rule_files:
  - '/etc/prometheus/alert.rules.yml'
```

Alert kurallarının tanımlandığı dosyaları belirtir. Birden fazla dosya eklenebilir.

#### 3. Alerting Bölümü

```yaml
alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - 'alertmanager:9093'
```

Prometheus, tetiklenen alert'leri hangi AlertManager instance'ina göndereceğini burada tanır. Docker network içinde `alertmanager:9093` olarak erişir.

#### 4. Scrape Configs Bölümü

```yaml
scrape_configs:
  # Prometheus'un kendisi
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
        labels:
          service: 'prometheus'

  # Order Service
  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s
    static_configs:
      - targets: ['order-service:8081']
        labels:
          service: 'order-service'
          team: 'backend'
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance
        replacement: 'order-service'
```

**Her job için önemli alanlar:**

| Alan | Açıklama |
|------|----------|
| `job_name` | Job'in benzersiz adı. Prometheus bu ismi `job` label'i olarak ekler |
| `metrics_path` | Metriklerin expose edildiği endpoint. Spring Boot için `/actuator/prometheus` |
| `scrape_interval` | Bu job için özel toplama sıklığı. Mikroservisler için 10s kullandım |
| `static_configs.targets` | Hedef adres(ler). Docker network içinde container ismi kullanılır |
| `labels` | Her metriğe eklenecek ek label'lar (filtreleme için) |
| `relabel_configs` | Metrik label'larını yeniden yazmak için. `instance` label'ini okunaklı hale getirdim |

Projede toplam **6 scrape job** tanımladım:

1. **prometheus** -- Prometheus'un kendi metrikleri (localhost:9090)
2. **order-service** -- Order servisinin metrikleri (order-service:8081)
3. **inventory-service** -- Inventory servisinin metrikleri (inventory-service:8082)
4. **payment-service** -- Payment servisinin metrikleri (payment-service:8083)
5. **alertmanager** -- AlertManager'ın metrikleri (alertmanager:9093)
6. **grafana** -- Grafana'nın metrikleri (grafana:3000)

### Prometheus Web UI Kullanımı

Prometheus, `http://localhost:9090` adresinde bir web arayüzü sunar. 3 ana sayfası var:

#### Targets Sayfası (`/targets`)

Bu sayfada tüm scrape hedeflerini ve durumlarını görebilirsin:

- **State: UP** -- Servis ayakta, metrikler başarıyla toplanıyor
- **State: DOWN** -- Servis erişilemez veya hata dönüyor
- **Last Scrape** -- Son başarılı metrik toplama zamanı
- **Scrape Duration** -- Metrik toplamanın ne kadar sürdüğü

#### Graph Sayfası (`/graph`)

PromQL sorguları yazarak metrikleri sorgulayabilir ve basit grafiklerle görselleştirebilirsin. Örnek:

```promql
rate(http_server_requests_seconds_count{job="order-service"}[5m])
```

Bu sorgu, order-service'e gelen HTTP isteklerinin saniye başına oranını gösterir.

#### Alerts Sayfası (`/alerts`)

Tanımlı alert kurallarını, durumlarını (inactive, pending, firing) ve ne zaman tetiklendiklerini görebilirsin.

### PromQL Temelleri

PromQL (Prometheus Query Language), Prometheus'un sorgu dilidir. Temel kavramları anlayalım:

#### Instant Vector

Belirli bir andaki değer. Doğrudan metrik adını yazmak yeterli:

```promql
process_cpu_usage{job="order-service"}
```

#### Range Vector

Belirli bir zaman aralığındaki tüm değerleri döndürür. Köşeli parantez içinde süre belirtilir:

```promql
http_server_requests_seconds_count{job="order-service"}[5m]
```

#### rate()

Counter tipi metriklerde saniye başına artış oranını hesaplar. **Counter metriklerinde mutlaka rate() veya increase() kullanılmalıdır**, çünkü counter'lar sürekli artan değerlerdir.

```promql
rate(http_server_requests_seconds_count[5m])
```

#### histogram_quantile()

Histogram bucket'larından percentile hesaplar. Örneğin p95 (yüzde 95'lik dilim):

```promql
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
```

#### sum by

Belirli bir label'a göre gruplama ve toplama yapar:

```promql
sum(rate(http_server_requests_seconds_count[5m])) by (job)
```

#### increase()

Belirli bir zaman aralığındaki toplam artışı döndürür. rate()'in zaman araligiyla çarpılmış hali gibi düşünülebilir:

```promql
increase(http_server_requests_seconds_count[1h])
```

---

## 3. Spring Boot Actuator ve Micrometer Entegrasyonu

### pom.xml Bağımlılıkları

Monitoring için sadece 2 dependency yeterli:

```xml
<!-- Spring Boot Actuator - Monitoring endpoint'leri icin -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Micrometer Prometheus Registry - Prometheus formatinda metrik export icin -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- **spring-boot-starter-actuator**: Uygulamaya `/actuator/*` endpoint'lerini ekler. Health check, metrik listesi, info gibi yönetim endpoint'leri sağlar.
- **micrometer-registry-prometheus**: Micrometer'in topladığı metrikleri Prometheus'un anladığı formata çevirir ve `/actuator/prometheus` endpoint'ini aktif eder.

> **Not:** Micrometer, SLF4J'nin metrik dünyasındaki karşılığı gibi düşünülebilir. SLF4J nasıl farklı logging framework'lerine (Logback, Log4j) kapı açıyorsa, Micrometer da farklı monitoring sistemlerine (Prometheus, Datadog, New Relic) kapı açar. Biz `micrometer-registry-prometheus` seçiyoruz ve çıktı formatımız Prometheus oluyor.

### application.yml Konfigurasyonu

```yaml
# Actuator Konfigurasyonu - Prometheus icin kritik!
management:
  endpoints:
    web:
      exposure:
        # Hangi actuator endpoint'leri disariya acilacak
        include: health,info,metrics,prometheus
      base-path: /actuator

  endpoint:
    health:
      show-details: always
    metrics:
      enabled: true
    prometheus:
      enabled: true

  # Metrics Konfigurasyonu
  metrics:
    export:
      prometheus:
        enabled: true
    # Her metrige application tag'i ekleniyor
    tags:
      application: ${spring.application.name}
    distribution:
      # Response time icin percentile'lar
      percentiles-histogram:
        http.server.requests: true
      slo:
        # Service Level Objective - 500ms, 1s, 2s icin bucket'lar
        http.server.requests: 500ms,1s,2s
```

**Her ayarın detaylı açıklaması:**

| Ayar | Değer | Açıklama |
|------|-------|----------|
| `endpoints.web.exposure.include` | health,info,metrics,prometheus | Sadece ihtiyacımız olan endpoint'leri açıyoruz. `*` ile tümünü açabilirdik ama güvenlik açısından gereksiz endpoint'leri kapatmak daha doğru. |
| `endpoint.health.show-details` | always | Health endpoint'inde detaylı bilgi gösterir (disk alanı, db bağlantısı vs.) |
| `endpoint.prometheus.enabled` | true | `/actuator/prometheus` endpoint'ini aktif eder |
| `metrics.tags.application` | ${spring.application.name} | Her metriğe `application` label'i ekler. Birden fazla servis olduğunda filtreleme için kritik. |
| `percentiles-histogram` | true | HTTP istekleri için histogram bucket'ları oluşturur. Bu sayede p50, p90, p95, p99 gibi percentile'ları PromQL ile hesaplayabiliriz. |
| `slo` | 500ms, 1s, 2s | SLO bazlı özel bucket'lar ekler. "İsteklerin yüzde kaçı 500ms altında?" gibi soruları cevaplayabilmek için. |

### /actuator/prometheus Endpoint'inin Çıktısı

Servis ayaktayken `http://localhost:8081/actuator/prometheus` adresine gittiğinde Prometheus formatında metrikleri görebilirsin. Çıktı şu formatta olur:

```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",application="order-service",} 2.5165824E7
jvm_memory_used_bytes{area="heap",id="G1 Survivor Space",application="order-service",} 1048576.0
jvm_memory_used_bytes{area="heap",id="G1 Old Gen",application="order-service",} 1.6777216E7
jvm_memory_used_bytes{area="nonheap",id="Metaspace",application="order-service",} 5.2428800E7

# HELP http_server_requests_seconds_count
# TYPE http_server_requests_seconds_count counter
http_server_requests_seconds_count{method="GET",uri="/api/orders",status="200",application="order-service",} 42.0

# HELP http_server_requests_seconds_bucket
# TYPE http_server_requests_seconds_bucket histogram
http_server_requests_seconds_bucket{method="GET",uri="/api/orders",status="200",le="0.5",} 40.0
http_server_requests_seconds_bucket{method="GET",uri="/api/orders",status="200",le="1.0",} 41.0
http_server_requests_seconds_bucket{method="GET",uri="/api/orders",status="200",le="2.0",} 42.0
http_server_requests_seconds_bucket{method="GET",uri="/api/orders",status="200",le="+Inf",} 42.0
```

Her satırın yapısı:

```
metrik_adi{label1="deger1", label2="deger2"} sayisal_deger
```

`# HELP` satırı metriğin açıklamasını, `# TYPE` satırı metrik tipini (gauge, counter, histogram, summary) belirtir.

### Neden Custom Metrik Yazmadan Başladık

Birçoğu "monitoring kuralım" dediğinde ilk iş custom metrik yazmaya başlar. Ben bu yaklaşımı benimsemiyorum çünkü:

1. **Default metrikler zaten çok kapsamlı.** JVM, HTTP, thread, GC, logback -- hepsi hazır geliyor.
2. **Öncelikle default metrikleri anlamak gerekir.** Heap memory nedir, GC pause ne demektir bilmeden custom metrik yazmanın bir anlamı yok.
3. **Custom metrikler bakım yükünü arttırır.** Her custom metrik için kod yazılır, test edilir ve dokümante edilir.
4. **Bu proje bir baseline.** Önce default metriklerle monitoring altyapısını kuruyoruz. Sonra business ihtiyaçlara göre custom metrik eklenir.

---

## 4. Default Metrikler - Tam Referans

### Projede Kullandığımız 14 Default Metrik

Aşağıdaki tablo, Grafana dashboard'unda ve alert kurallarinda kullandığımız metrikleri listeler:

| # | Metrik Adı | Tip | Açıklama | Kullanım                                          |
|---|-----------|-----|----------|---------------------------------------------------|
| 1 | `up` | Gauge | Hedef servis ayakta mi? (1=UP, 0=DOWN) | Service status panelleri + ServiceDown alert      |
| 2 | `process_uptime_seconds` | Gauge | Prosesin ne zamandir çalıştığı (saniye) | Uptime paneli + ServiceRecentlyRestarted alert    |
| 3 | `http_server_requests_seconds_count` | Counter | Toplam HTTP istek sayısı | Request rate paneli + hata oran alert'leri        |
| 4 | `http_server_requests_seconds_bucket` | Histogram | HTTP yanıt süresi dağılımı (bucket'lar) | Response time p95 paneli + SlowResponseTime alert |
| 5 | `process_cpu_usage` | Gauge | JVM proses CPU kullanımı (0-1 arası) | CPU usage paneli + HighCPUUsage alert             |
| 6 | `system_cpu_usage` | Gauge | Tüm sistemin CPU kullanımı (0-1 arası) | System CPU gauge paneli + HighSystemCPU alert     |
| 7 | `jvm_memory_used_bytes` | Gauge | JVM memory kulllanimi (byte) | Heap memory panelleri + HighHeapMemory alert      |
| 8 | `jvm_memory_max_bytes` | Gauge | JVM'e ayrılmış maksimum memory (byte) | Heap % hesaplaması                                |
| 9 | `jvm_threads_live_threads` | Gauge | Canlı thread sayısı | Live threads paneli + HighThreadCount alert       |
| 10 | `jvm_threads_states_threads` | Gauge | Thread durumlarına göre dağılım | Thread states paneli + ThreadDeadlockRisk alert   |
| 11 | `jvm_gc_pause_seconds_sum` | Counter | Toplam GC duraklama süresi | GC pause paneli + HighGCPause alert               |
| 12 | `jvm_gc_pause_seconds_count` | Counter | Toplam GC duraklama sayısı | FrequentGC alert                                  |
| 13 | `logback_events_total` | Counter | Log seviyesine göre log sayısı | Log error rate paneli + HighErrorLogRate alert    |
| 14 | `jvm_classes_loaded_classes` | Gauge | Yüklenmiş class sayısı | Loaded classes paneli                             |

### Projede Kullanmadığımız Ama Kullanılabilecek Ek Default Metrikler

Spring Boot Actuator + Micrometer, yukarıdakilerin yanında onlarca ek metrik daha sunar:

#### Disk I/O Metrikleri

| Metrik | Tip | Açıklama | Örnek PromQL |
|--------|-----|----------|-------------|
| `disk_total_bytes` | Gauge | Toplam disk alanı | `disk_total_bytes{job="order-service"}` |
| `disk_free_bytes` | Gauge | Boş disk alanı | `disk_free_bytes{job="order-service"}` |

```promql
-- Disk kullanim yuzdesi
1 - (disk_free_bytes / disk_total_bytes)
```

#### Connection Pool Metrikleri (HikariCP)

H2 veritabanı eklendigi için HikariCP metrikleri otomatik olarak gelmektedir:

| Metrik | Tip | Açıklama | Örnek PromQL |
|--------|-----|----------|-------------|
| `hikaricp_connections_active` | Gauge | Aktif bağlantı sayısı | `hikaricp_connections_active{job="order-service"}` |
| `hikaricp_connections_idle` | Gauge | Boşta bekleyen bağlantı sayısı | `hikaricp_connections_idle{job="order-service"}` |
| `hikaricp_connections_pending` | Gauge | Bağlantı bekleyen thread sayısı | `hikaricp_connections_pending{job="order-service"}` |
| `hikaricp_connections_max` | Gauge | Maksimum pool boyutu | `hikaricp_connections_max{job="order-service"}` |
| `hikaricp_connections_timeout_total` | Counter | Timeout olan bağlantı istek sayısı | `rate(hikaricp_connections_timeout_total[5m])` |

```promql
-- Connection pool kullanim yuzdesi
hikaricp_connections_active / hikaricp_connections_max
```

#### Tomcat Metrikleri

Embedded Tomcat metrikleri varsayılan olarak gelir:

| Metrik | Tip | Açıklama | Örnek PromQL |
|--------|-----|----------|-------------|
| `tomcat_sessions_active_current_sessions` | Gauge | Aktif session sayısı | `tomcat_sessions_active_current_sessions` |
| `tomcat_sessions_created_sessions_total` | Counter | Oluşturulan toplam session | `rate(tomcat_sessions_created_sessions_total[5m])` |
| `tomcat_threads_current_threads` | Gauge | Tomcat thread pool büyüklüğü | `tomcat_threads_current_threads{job="order-service"}` |
| `tomcat_threads_busy_threads` | Gauge | Meşgul Tomcat thread sayısı | `tomcat_threads_busy_threads{job="order-service"}` |

```promql
-- Tomcat thread pool kullanim yuzdesi
tomcat_threads_busy_threads / tomcat_threads_current_threads
```

#### JVM Buffer Metrikleri

| Metrik | Tip | Açıklama | Örnek PromQL |
|--------|-----|----------|-------------|
| `jvm_buffer_memory_used_bytes` | Gauge | Kullanılan buffer memory | `jvm_buffer_memory_used_bytes{id="direct"}` |
| `jvm_buffer_total_capacity_bytes` | Gauge | Toplam buffer kapasitesi | `jvm_buffer_total_capacity_bytes{id="direct"}` |
| `jvm_buffer_count_buffers` | Gauge | Buffer sayısı | `jvm_buffer_count_buffers{id="direct"}` |

#### Non-Heap Memory Metrikleri

| Metrik | Tip | Açıklama | Örnek PromQL |
|--------|-----|----------|-------------|
| `jvm_memory_used_bytes{area="nonheap"}` | Gauge | Non-heap (Metaspace, Code Cache) kullanımı | `jvm_memory_used_bytes{area="nonheap", job="order-service"}` |
| `jvm_memory_committed_bytes{area="nonheap"}` | Gauge | OS'tan alinmis non-heap memory | `jvm_memory_committed_bytes{area="nonheap"}` |

```promql
-- Metaspace kullanimi
jvm_memory_used_bytes{area="nonheap", id="Metaspace"}
```

#### Class Loading Metrikleri (Detaylı)

| Metrik | Tip | Açıklama | Örnek PromQL |
|--------|-----|----------|-------------|
| `jvm_classes_loaded_classes` | Gauge | Şu an yüklenmiş class sayısı | `jvm_classes_loaded_classes{job="order-service"}` |
| `jvm_classes_unloaded_classes_total` | Counter | Toplam unload edilen class sayısı | `rate(jvm_classes_unloaded_classes_total[5m])` |

```promql
-- Class loading orani (yuksek ise classloader leak olabilir)
rate(jvm_classes_unloaded_classes_total[5m])
```

#### Process Metrikleri

| Metrik | Tip | Açıklama | Örnek PromQL |
|--------|-----|----------|-------------|
| `process_files_open_files` | Gauge | Açık dosya sayısı | `process_files_open_files{job="order-service"}` |
| `process_files_max_files` | Gauge | Maksimum açılabilecek dosya sayısı | `process_files_max_files{job="order-service"}` |
| `process_start_time_seconds` | Gauge | Prosesin başlatılma zamanı (epoch) | `process_start_time_seconds{job="order-service"}` |

```promql
-- Acik dosya yuzdesi (file descriptor leak tespiti)
process_files_open_files / process_files_max_files
```

---

## 5. Grafana Kurulumu ve Dashboard Implementasyonu

### docker-compose.yml İçindeki Grafana Konfigurasyonu

```yaml
grafana:
  image: grafana/grafana:10.2.2
  container_name: grafana
  ports:
    - "3000:3000"
  networks:
    - ecommerce-network
  volumes:
    - grafana-data:/var/lib/grafana
    - ./monitoring/grafana/provisioning:/etc/grafana/provisioning
    - ./monitoring/grafana/dashboards:/var/lib/grafana/dashboards
  environment:
    - GF_SECURITY_ADMIN_USER=admin
    - GF_SECURITY_ADMIN_PASSWORD=admin
    - GF_USERS_ALLOW_SIGN_UP=false
    - GF_SERVER_ROOT_URL=http://localhost:3000
    - GF_INSTALL_PLUGINS=grafana-piechart-panel
  restart: unless-stopped
  depends_on:
    - prometheus
```

**Environment variable'ların açıklaması:**

| Variable | Değer | Açıklama |
|----------|-------|----------|
| `GF_SECURITY_ADMIN_USER` | admin | Varsayılan admin kullanıcı adı |
| `GF_SECURITY_ADMIN_PASSWORD` | admin | Varsayılan admin şifresi. Production'da mutlaka değiştirin! |
| `GF_USERS_ALLOW_SIGN_UP` | false | Self-registration kapalı |
| `GF_SERVER_ROOT_URL` | http://localhost:3000 | Grafana'nın kendi URL'i (linklerde kullanılır) |
| `GF_INSTALL_PLUGINS` | grafana-piechart-panel | Ek plugin yükleme |

### Provisioning Sistemi

Grafana'nın en güçlü özelliklerinden biri **provisioning**. Bu sistem sayesinde Grafana her başladığında datasource'lar ve dashboard'lar otomatik olarak yüklenir. Elle konfigürasyon yapmanıza gerek kalmaz.

#### Datasource Provisioning

**Dosya:** `monitoring/grafana/provisioning/datasources/prometheus.yml`

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: true
    jsonData:
      httpMethod: POST
      timeInterval: "15s"
```

| Alan | Açıklama |
|------|----------|
| `type: prometheus` | Datasource tipi |
| `access: proxy` | Grafana backend üzerinden Prometheus'a erişir (browser değil) |
| `url: http://prometheus:9090` | Docker network içindeki Prometheus adresi |
| `isDefault: true` | Varsayılan datasource olarak işaretle |
| `httpMethod: POST` | PromQL sorgularını POST ile gönder (uzun sorgularda GET URL limiti aşabilir) |
| `timeInterval: "15s"` | Minimum zaman aralığı (Prometheus scrape interval ile aynı) |

#### Dashboard Provider

**Dosya:** `monitoring/grafana/provisioning/dashboards/default.yml`

```yaml
apiVersion: 1

providers:
  - name: 'E-Commerce Dashboards'
    orgId: 1
    folder: 'E-Commerce'
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    allowUiUpdates: true
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: true
```

| Alan | Açıklama |
|------|----------|
| `folder: 'E-Commerce'` | Dashboard'larin gösterileceği Grafana klasörü |
| `type: file` | Dashboard JSON dosyalarından yükle |
| `updateIntervalSeconds: 10` | Her 10 saniyede bir dosya değişikliği kontrol et |
| `allowUiUpdates: true` | Grafana UI'dan dashboard düzenlemesine izin ver |
| `path` | Dashboard JSON dosyalarinin bulundugu dizin |

### 18 Panelin Açıklaması

Dashboard'umuzdaki her paneli, ne gösterdiğini ve hangi PromQL sorgusunu kullandığını tek tek açıklıyorum:

#### Row 1: Service Status (y=0, 4 panel)

**Panel 1 -- Order Service Status** (Stat panel)

```promql
up{job="order-service"}
```

Servisin ayakta olup olmadığını gösterir. Değer 1 ise yeşil "UP", 0 ise kırmızı "DOWN" yazar. Value mapping ile sayı yerine metin gösterilir.

**Panel 2 -- Inventory Service Status** (Stat panel)

```promql
up{job="inventory-service"}
```

Aynı mantık, inventory service için.

**Panel 3 -- Payment Service Status** (Stat panel)

```promql
up{job="payment-service"}
```

Aynı mantık, payment service için.

**Panel 4 -- Service Uptime (hours)** (Stat panel)

```promql
process_uptime_seconds{job=~"order-service|inventory-service|payment-service"} / 3600
```

Her servisin kac saattir kesintisiz çalıştığını gösterir. Değer saat cinsinden gösterilir (saniye / 3600).

---

#### Row 2: HTTP Metrikleri (y=4, 3 panel)

**Panel 5 -- Request Rate (per second)** (Time series)

```promql
sum(rate(http_server_requests_seconds_count{
  job=~"order-service|inventory-service|payment-service"
}[5m])) by (job)
```

Her servis için saniye başına gelen HTTP istek sayısı. `rate()` fonksiyonu counter'in artış hızını hesaplar, `sum by (job)` ise tüm URI ve method'ları job bazında toplar. Legend'da mean, last ve max değerleri gösterilir.

**Panel 6 -- Response Time (p95)** (Time series)

```promql
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket{
    job=~"order-service|inventory-service|payment-service"
  }[5m])) by (job, le)
)
```

HTTP yanıt sürelerinin 95. yüzdelik dilimi. Yani isteklerin %95'i bu süreden kısa sürede tamamlanıyor. Threshold'lar: 2s'de sarı, 5s'de kırmızı çizgi. `le` label'i (less than or equal) histogram bucket sınırlarını temsil eder.

**Panel 7 -- HTTP Error Rate (5xx %)** (Time series)

```promql
sum(rate(http_server_requests_seconds_count{
  job=~"order-service|inventory-service|payment-service",
  status=~"5.."
}[5m])) by (job)
/
sum(rate(http_server_requests_seconds_count{
  job=~"order-service|inventory-service|payment-service"
}[5m])) by (job)
```

5xx hatalarının toplam isteklere oranı. %5'in üzerinde kırmızı threshold çizgisi var. Bu oran anlık spike'ları değil, 5 dakikalik penceredeki ortalama hata oranını gösterir.

---

#### Row 3: Resource Usage (y=12, 3 panel)

**Panel 8 -- CPU Usage (per service)** (Time series)

```promql
process_cpu_usage{job=~"order-service|inventory-service|payment-service"}
```

Her servisin JVM proses CPU kullanımı. 0-1 arası değer (percentunit). Threshold'lar: 0.80'de sarı, 0.95'te kırmızı.

**Panel 9 -- Heap Memory Usage (%)** (Time series)

```promql
jvm_memory_used_bytes{job=~"...", area="heap"}
/
jvm_memory_max_bytes{job=~"...", area="heap"}
```

Heap memory kullanım yüzdesi. Kullanılan heap / maksimum heap. Threshold'lar: %85'te sarı, %95'te kırmızı. Bu oran sürekli yüksekse memory leak olabilir.

**Panel 10 -- Heap Memory Used (bytes)** (Time series)

```promql
jvm_memory_used_bytes{job=~"...", area="heap"}
```

Mutlak heap memory kullanımı (byte cinsinden). Y ekseni `bytes` formatında gösterilir (MB/GB otomatik). Oran panelinin yanında mutlak değeri de görmek, "aslında ne kadar memory kullanıyoruz?" sorusunu cevaplar.

---

#### Row 4: Threads ve GC (y=20, 3 panel)

**Panel 11 -- Live Threads** (Time series)

```promql
jvm_threads_live_threads{job=~"order-service|inventory-service|payment-service"}
```

Her servisteki canlı thread sayısı. 200'un üzerinde kırmızı threshold çizgisi var. Normal bir Spring Boot uygulamasında 30-80 arası thread beklenir. Sürekli artıyorsa thread leak olabilir.

**Panel 12 -- Thread States** (Time series, stacked)

```promql
jvm_threads_states_threads{job=~"order-service|inventory-service|payment-service"}
```

Thread'lerin durumlarına göre dağılımı: RUNNABLE, WAITING, TIMED_WAITING, BLOCKED, NEW, TERMINATED. Stacked (yığılmış) grafik olarak gösterilir. BLOCKED thread sayısının artması deadlock riskine işaret eder.

**Panel 13 -- GC Pause Duration (rate)** (Time series)

```promql
rate(jvm_gc_pause_seconds_sum{
  job=~"order-service|inventory-service|payment-service"
}[5m])
```

Garbage Collection duraklama süresinin oranı. GC tipi (cause) ve aksiyonu (action) legend'da gösterilir. Yüksek GC pause değerleri, uygulamanin "stop-the-world" duraklamaları yaşadığını gösterir.

---

#### Row 5: System ve Log (y=28, 3 panel)

**Panel 14 -- System CPU** (Gauge)

```promql
system_cpu_usage{job=~"order-service|inventory-service|payment-service"}
```

Host makinenin toplam CPU kullanımı. Gauge (gösterge) paneli olarak görselleştirilir. %70'in üzerinde sarı, %90'in üzerinde kırmızı. `process_cpu_usage`'dan farki: bu metrik tüm sistemin CPU'sunu gösterir, sadece JVM'in değil.

**Panel 15 -- Log Error Rate (/s)** (Time series)

```promql
rate(logback_events_total{
  job=~"order-service|inventory-service|payment-service",
  level="error"
}[5m])
```

Saniye başına üretilen ERROR log sayısı. 0.5/s'nin üzerinde kırmızı threshold. Logback entegrasyonu sayesinde her log seviyesi ayrı metrik olarak izlenir.

**Panel 16 -- Loaded Classes** (Stat panel)

```promql
jvm_classes_loaded_classes{job=~"order-service|inventory-service|payment-service"}
```

Her serviste yüklü olan Java class sayısı. Normal bir Spring Boot uygulamasında 8000-15000 arası değer beklenir. Sürekli artıyorsa classloader leak olabilir.

---

#### Row 6: Endpoint Metrics (y=34, 2 panel)

**Panel 17 -- Request Count by Endpoint** (Time series)

```promql
sum(rate(http_server_requests_seconds_count{
  job=~"order-service|inventory-service|payment-service",
  uri!~"/actuator.*"
}[5m])) by (job, method, uri)
```

Her endpoint için saniye başına istek sayısı. `uri!~"/actuator.*"` filtresi ile Actuator endpoint'leri hariç tutulur, sadece uygulama endpoint'leri gösterilir. Legend'da servis adı, HTTP method ve URI bilgisi yer alır (örneğin `order-service GET /api/orders`). Bu panel sayesinde hangi endpoint'e ne kadar trafik geldiğini görebilirsin.

**Panel 18 -- Response Time by Endpoint (p95)** (Time series)

```promql
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket{
    job=~"order-service|inventory-service|payment-service",
    uri!~"/actuator.*"
  }[5m])) by (job, method, uri, le)
)
```

Her endpoint için 95. yüzdelik dilim yanıt süresi. Row 2'deki genel Response Time panelinden farkı: burada tüm endpoint'ler ayrı ayrı gösterilir. Hangi endpoint'in yavaş olduğunu tespit etmek için kullanılır. Actuator endpoint'leri yine hariç tutulur. Legend'da servis, method ve URI bazında kırılım verilir.

### Dashboard JSON Yapısı ve Import/Export

#### Export

Grafana UI'dan dashboard'u export etmek için:

1. Dashboard'u aç
2. Sağ üstteki **Share** butonuna tıkla
3. **Export** sekmesine geç
4. **Save to file** ile JSON olarak indir

#### Import

1. Grafana ana sayfasında sol menuden **Dashboards** > **Import**
2. JSON dosyasını yükle veya dashboard ID gir
3. Prometheus datasource'u seç
4. **Import** butonuna tıkla

#### Provisioning ile Otomatik Yükleme (bizim yöntemimiz)

Biz dashboard JSON dosyasını `monitoring/grafana/dashboards/` dizinine koyduk. Grafana her başladığında bu dosyayı otomatik okur ve dashboard'u oluşturur. Elle import gerekmez.

### Grafana'ya İlk Giriş

1. `http://localhost:3000` adresine git
2. Kullanıcı adı: `admin`, şifre: `admin`
3. İlk girişte şifre değiştirme isteği gelecek (Skip edebilirsin, ama production'da değiştir)
4. Sol menüde **Dashboards** > **E-Commerce** klasörüne git
5. **E-Commerce System Overview** dashboard'unu aç

---

## 6. Alert Sistemi

### Genel Bakış

Alert sistemimiz 3 katmandan oluşur:

1. **Prometheus Alert Rules** -- Hangi koşullarda alert tetikleneceğini tanımlar
2. **Prometheus Alert Engine** -- Kuralları düzenli olarak değerlendirir
3. **AlertManager** -- Tetiklenen alert'leri alır, gruplar, route eder ve bildirim gönderir

### Prometheus Alert Kuralları

**Dosya:** `monitoring/prometheus/alert.rules.yml`

5 grup altında toplam **18 alert** tanımladım:

#### Grup 1: Service Availability (2 alert)

| Alert | Severity | Koşul | Bekleme | Açıklama |
|-------|----------|-------|---------|----------|
| **ServiceDown** | critical | `up == 0` | 1m | Servis 1 dakikadır DOWN |
| **ServiceFlapping** | warning | `changes(up[10m]) > 2` | - | 10 dakikada 3+ kez UP/DOWN |

ServiceDown alert'i en kritik alert'imiz. Bir servis 1 dakika boyunca erişilemezse hemen tetiklenir. ServiceFlapping ise servisin sürekli açılıp kapandığını (crash loop) tespit eder.

```promql
-- ServiceDown
up{job=~"order-service|inventory-service|payment-service"} == 0

-- ServiceFlapping
changes(up{job=~"order-service|inventory-service|payment-service"}[10m]) > 2
```

#### Grup 2: HTTP Requests ve Error Rates (5 alert)

| Alert | Severity | Koşul | Bekleme | Açıklama |
|-------|----------|-------|---------|----------|
| **HighHTTP5xxRate** | critical | 5xx oranı > %5 | 3m | Yüksek sunucu hatası oranı |
| **HighHTTP4xxRate** | warning | 4xx oranı > %20 | 5m | Yüksek istemci hatası oranı |
| **SlowResponseTime** | warning | p95 > 2s | 3m | Yavaş yanıt süresi |
| **VerySlowResponseTime** | critical | p95 > 5s | 2m | Çok yavaş yanıt süresi |
| **NoRequestsReceived** | warning | rate == 0 | 5m | Hiç istek gelmiyor |

```promql
-- HighHTTP5xxRate
(
  rate(http_server_requests_seconds_count{status=~"5..", job=~"..."}[5m])
  /
  rate(http_server_requests_seconds_count{job=~"..."}[5m])
) > 0.05

-- SlowResponseTime
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{job=~"..."}[5m])
) > 2
```

#### Grup 3: JVM Resources - CPU ve Memory (5 alert)

| Alert | Severity | Koşul | Bekleme | Açıklama |
|-------|----------|-------|---------|----------|
| **HighCPUUsage** | warning | process_cpu > %80 | 5m | Yüksek CPU kullanımı |
| **CriticalCPUUsage** | critical | process_cpu > %95 | 2m | Kritik CPU kullanımı |
| **HighHeapMemory** | warning | heap % > %85 | 5m | Yüksek heap kullanımı |
| **CriticalHeapMemory** | critical | heap % > %95 | 2m | Kritik heap kullanımı |
| **HighSystemCPU** | warning | system_cpu > %90 | 5m | Yüksek sistem CPU'su |

```promql
-- HighHeapMemory
(
  jvm_memory_used_bytes{area="heap", job=~"..."}
  /
  jvm_memory_max_bytes{area="heap", job=~"..."}
) > 0.85
```

#### Grup 4: JVM Threads ve GC (4 alert)

| Alert | Severity | Koşul | Bekleme | Açıklama |
|-------|----------|-------|---------|----------|
| **HighThreadCount** | warning | live threads > 200 | 5m | Yüksek thread sayısı |
| **ThreadDeadlockRisk** | critical | blocked threads > 10 | 3m | Deadlock riski |
| **HighGCPause** | warning | ortalama GC pause > 500ms | 5m | Yüksek GC duraklaması |
| **FrequentGC** | warning | GC oranı > 0.5/s | 5m | Çok sık GC çalışması |

```promql
-- ThreadDeadlockRisk
jvm_threads_states_threads{state="blocked", job=~"..."} > 10

-- HighGCPause
(
  rate(jvm_gc_pause_seconds_sum{job=~"..."}[5m])
  /
  rate(jvm_gc_pause_seconds_count{job=~"..."}[5m])
) > 0.5
```

#### Grup 5: Log Errors ve Uptime (2 alert)

| Alert | Severity | Koşul | Bekleme | Açıklama |
|-------|----------|-------|---------|----------|
| **HighErrorLogRate** | warning | error log > 0.5/s | 3m | Yüksek hata log oranı |
| **ServiceRecentlyRestarted** | info | uptime < 300s | - | Servis yakın zamanda yeniden başladı |

```promql
-- HighErrorLogRate
rate(logback_events_total{level="error", job=~"..."}[5m]) > 0.5

-- ServiceRecentlyRestarted
process_uptime_seconds{job=~"..."} < 300
```

### AlertManager Konfigurasyonu

**Dosya:** `monitoring/alertmanager/alertmanager.yml`

#### Routing Yapısı

```
Gelen Alert
    |
    +-- severity: critical --> critical-team (Email)
    |                          group_wait: 5s
    |                          repeat_interval: 30m
    |
    +-- severity: warning  --> warning-team (Email)
    |                          repeat_interval: 2h
    |
    +-- (diger)            --> default-team (Email)
                               repeat_interval: 4h
```

**Route parametreleri:**

| Parametre | Değer | Açıklama |
|-----------|-------|----------|
| `group_by` | alertname, severity | Alert'leri bu label'lara göre grupla |
| `group_wait` | 10s (default), 5s (critical) | İlk alert geldiğinde grup tamamlansin diye bekle |
| `group_interval` | 10s | Gruba yeni alert eklendiğinde tekrar bildirim göndermeden önce bekle |
| `repeat_interval` | 4h/2h/30m | Aynı alert için tekrar bildirim gönderme sıklığı |

#### 3 Receiver (Alıcı)

**1. default-team** -- Genel ekip, sadece email

```yaml
receivers:
  - name: 'default-team'
    email_configs:
      - to: 'team@ecommerce.com'
        headers:
          Subject: '[ALERT] {{ .GroupLabels.alertname }}'
```

**2. critical-team** -- On-call ekip + CTO, sadece email

```yaml
  - name: 'critical-team'
    email_configs:
      - to: 'oncall@ecommerce.com, cto@ecommerce.com'
        headers:
          Subject: '[CRITICAL] {{ .GroupLabels.alertname }}'
```

**3. warning-team** -- Genel ekip, sadece email

```yaml
  - name: 'warning-team'
    email_configs:
      - to: 'team@ecommerce.com'
        headers:
          Subject: '[WARNING] {{ .GroupLabels.alertname }}'
```

#### Inhibition (Bastırma) Kuralları

Inhibition, bir alert tetiklendiğinde ilişkili diğer alert'leri bastırmak için kullanılır:

**Kural 1:** ServiceDown aktifse, aynı servisin diğer tüm alert'leri bastırılır.

```yaml
- source_match:
    alertname: 'ServiceDown'
  target_match_re:
    alertname: '.*'
  equal: ['job']
```

Mantık: Servis zaten DOWN ise, o servisin CPU veya memory alert'leri gereksiz gürültü olur.

**Kural 2:** Aynı alert'in critical versiyonu aktifse, warning versiyonu bastırılır.

```yaml
- source_match:
    severity: 'critical'
  target_match:
    severity: 'warning'
  equal: ['alertname', 'job']
```

Mantık: CriticalHeapMemory (>%95) tetiklenmişse, HighHeapMemory (>%85) zaten tetiklenmiştir ve ayrı bildirim göndermenin anlamı yoktur.

### Alert Severity Seviyeleri

| Severity | Anlam | Aksiyon | Receiver |
|----------|-------|---------|----------|
| **critical** | Acil müdahale gerekli | Anında kontrol et ve çöz | critical-team (Email) |
| **warning** | Dikkat edilmeli, ama acil değil | İş saatlerinde incele | warning-team (Email) |
| **info** | Bilgilendirme | Log'a bak, planlama yap | default-team (Email) |

### Alert Lifecycle

Bir alert'in yaşam döngüsü şu aşamalardan oluşur:

```
+----------+     koşul sağlandığında     +----------+     "for" süresi dolunca      +----------+
| INACTIVE | --------------------------> | PENDING  | ----------------------------> |  FIRING  |
+----------+                             +----------+                               +----------+
     ^                                        |                                         |
     |          koşul artık sağlanmıyor       |         koşul artık sağlanmıyor         |
     +----------------------------------------+                                         |
     |                                                                                  |
     +-------------------------------------- RESOLVED <---------------------------------+
```

- **Inactive:** Kural tanımlı ama koşul sağlanmıyor. Normal durum.
- **Pending:** Koşul sağlandı ama henüz `for` süresi dolmadı. Geçici spike'ları filtrelemek için bu mekanizma var.
- **Firing:** Koşul `for` süresince sürekli sağlandı. AlertManager'a bildirim gönderilir.
- **Resolved:** Koşul artık sağlanmıyor. AlertManager "çözüldü" bildirimi gönderir.

Örnek: `HighHeapMemory` alert'inin `for: 5m` değeri var. Heap %85'i geçtiğinde alert PENDING'e geçer. Eğer 5 dakika boyunca heap hep %85'in üzerinde kalırsa FIRING olur. Heap %85'in altına düştüğünde RESOLVED olur.

### Email Notification Ayarları

#### Email (SMTP) Ayarları

```yaml
global:
  smtp_smarthost: 'smtp.gmail.com:587'
  smtp_from: 'alerts@ecommerce.com'
  smtp_auth_username: 'your-email@gmail.com'
  smtp_auth_password: 'your-app-password'
  smtp_require_tls: true
```

> **Önemli:** Gmail kullanıyorsanız, normal şifre yerine **App Password** oluşturmanız gerekir. Google hesap ayarlarından 2FA'yi aktif edin, sonra App Password oluşturun.

---

<a id="7-projeyi-ayaga-kaldirma-hands-on"></a>

## 7. Projeyi Ayağa Kaldırma (Hands-on)

### Adım 1: Projeyi Başlat

```bash
docker-compose up -d --build
```

Bu komut:
- 3 mikroservisi build eder (Dockerfile'lardan)
- Prometheus, AlertManager ve Grafana container'larını başlatır
- Tüm container'ları `ecommerce-network` üzerinde birbirine bağlar

### Adım 2: Container Durumlarını Kontrol Et

```bash
docker-compose ps
```

Beklenen çıktı:

```
NAME                STATUS              PORTS
order-service       Up (healthy)        0.0.0.0:8081->8081/tcp
inventory-service   Up (healthy)        0.0.0.0:8082->8082/tcp
payment-service     Up (healthy)        0.0.0.0:8083->8083/tcp
prometheus          Up                  0.0.0.0:9090->9090/tcp
alertmanager        Up                  0.0.0.0:9093->9093/tcp
grafana             Up                  0.0.0.0:3000->3000/tcp
```

### Adım 3: Servis Health Check

Her servisin ayakta olduğundan emin ol:

```bash
# Order Service
curl -s http://localhost:8081/actuator/health | python3 -m json.tool

# Inventory Service
curl -s http://localhost:8082/actuator/health | python3 -m json.tool

# Payment Service
curl -s http://localhost:8083/actuator/health | python3 -m json.tool
```

Beklenen çıktı:

```json
{
    "status": "UP",
    "components": {
        "diskSpace": {
            "status": "UP"
        },
        "ping": {
            "status": "UP"
        }
    }
}
```

### Adım 4: Prometheus Targets Kontrolü

`http://localhost:9090/targets` adresine git. 6 target'in hepsinin **UP** olduğunu doğrula:

- `prometheus` (1/1 up)
- `order-service` (1/1 up)
- `inventory-service` (1/1 up)
- `payment-service` (1/1 up)
- `alertmanager` (1/1 up)
- `grafana` (1/1 up)

### Adım 5: Grafana'ya Giriş

1. `http://localhost:3000` adresine git
2. Kullanıcı: `admin`, Şifre: `admin`
3. Sol menüde **Dashboards** > **E-Commerce** > **E-Commerce System Overview**
4. 3 servisin de "UP" olduğunu üst satırda göreceksin

### Adım 6: Metriklerin Geldiğini Doğrulama

```bash
# Prometheus formatinda metrikleri gor
curl -s http://localhost:8081/actuator/prometheus | head -50

# Belirli bir metrigi filtrele
curl -s http://localhost:8081/actuator/prometheus | grep "http_server_requests"
```

Prometheus UI'dan da doğrulama yapabilirsin:

1. `http://localhost:9090/graph` adresine git
2. Sorgu kutusuna yaz: `http_server_requests_seconds_count{job="order-service"}`
3. **Execute** butonuna bas
4. Sonuclarda gönderdiğin isteklerin sayısını göreceksin

---

<a id="8-promql-sorgu-ornekleri-cheat-sheet"></a>

## 8. PromQL Sorgu Örnekleri (Cheat Sheet)

### Temel Sorgular

```promql
-- Servis ayakta mi?
up{job="order-service"}

-- JVM heap memory kullanimi
jvm_memory_used_bytes{job="order-service", area="heap"}

-- Canli thread sayisi
jvm_threads_live_threads{job="order-service"}

-- Proses uptime (saat cinsinden)
process_uptime_seconds{job="order-service"} / 3600

-- Yuklenmis class sayisi
jvm_classes_loaded_classes{job="order-service"}
```

### Rate ve Increase

```promql
-- Saniye basina istek orani (son 5 dakika)
rate(http_server_requests_seconds_count{job="order-service"}[5m])

-- Son 1 saatteki toplam istek artisi
increase(http_server_requests_seconds_count{job="order-service"}[1h])

-- Saniye basina error log orani
rate(logback_events_total{job="order-service", level="error"}[5m])

-- Son 5 dakikadaki GC duraklama orani
rate(jvm_gc_pause_seconds_sum{job="order-service"}[5m])
```

### Histogram Quantile

```promql
-- p50 (medyan) yanit suresi
histogram_quantile(0.50,
  rate(http_server_requests_seconds_bucket{job="order-service"}[5m])
)

-- p90 yanit suresi
histogram_quantile(0.90,
  rate(http_server_requests_seconds_bucket{job="order-service"}[5m])
)

-- p95 yanit suresi
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{job="order-service"}[5m])
)

-- p99 yanit suresi
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket{job="order-service"}[5m])
)

-- Tum servisler icin p95 (job bazinda gruplama)
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket{
    job=~"order-service|inventory-service|payment-service"
  }[5m])) by (job, le)
)
```

### Aggregation (sum, avg, max, min by)

```promql
-- Tum servislerin toplam istek orani
sum(rate(http_server_requests_seconds_count[5m]))

-- Servis bazinda toplam istek orani
sum(rate(http_server_requests_seconds_count[5m])) by (job)

-- Servis bazinda ortalama CPU kullanimi
avg(process_cpu_usage) by (job)

-- En yuksek heap kullanimi olan servis
max(jvm_memory_used_bytes{area="heap"}) by (job)

-- En dusuk uptime'a sahip servis
min(process_uptime_seconds) by (job)

-- HTTP method bazinda istek sayisi
sum(rate(http_server_requests_seconds_count{job="order-service"}[5m])) by (method)

-- Status code bazinda istek sayisi
sum(rate(http_server_requests_seconds_count{job="order-service"}[5m])) by (status)

-- URI bazinda istek sayisi
sum(rate(http_server_requests_seconds_count{job="order-service"}[5m])) by (uri)
```

### Label Filtreleme

```promql
-- Tek servis
http_server_requests_seconds_count{job="order-service"}

-- Birden fazla servis (regex)
http_server_requests_seconds_count{job=~"order-service|payment-service"}

-- Belirli bir status code haric
http_server_requests_seconds_count{status!="200"}

-- 5xx hatalari (regex)
http_server_requests_seconds_count{status=~"5.."}

-- 4xx hatalari (regex)
http_server_requests_seconds_count{status=~"4.."}

-- Belirli bir URI
http_server_requests_seconds_count{uri="/api/orders"}

-- /actuator endpoint'leri haric
http_server_requests_seconds_count{uri!~"/actuator.*"}

-- Sadece GET istekleri
http_server_requests_seconds_count{method="GET"}

-- Heap memory (nonheap haric)
jvm_memory_used_bytes{area="heap"}

-- Belirli bir GC tipi
jvm_gc_pause_seconds_count{cause="Metadata GC Threshold"}
```

### Alert'lerde Kullanılan Sorgular

```promql
-- ServiceDown: Servis calismiyor mu?
up{job=~"order-service|inventory-service|payment-service"} == 0

-- ServiceFlapping: Servis 10dk'da 3+ kez durum degistirdi mi?
changes(up{job=~"order-service|inventory-service|payment-service"}[10m]) > 2

-- HighHTTP5xxRate: 5xx hata orani %5'ten fazla mi?
(
  rate(http_server_requests_seconds_count{status=~"5..", job=~"order-service|inventory-service|payment-service"}[5m])
  /
  rate(http_server_requests_seconds_count{job=~"order-service|inventory-service|payment-service"}[5m])
) > 0.05

-- SlowResponseTime: p95 yanis suresi 2 saniyeden fazla mi?
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{job=~"order-service|inventory-service|payment-service"}[5m])
) > 2

-- HighHeapMemory: Heap kullanimi %85'ten fazla mi?
(
  jvm_memory_used_bytes{job=~"order-service|inventory-service|payment-service", area="heap"}
  /
  jvm_memory_max_bytes{job=~"order-service|inventory-service|payment-service", area="heap"}
) > 0.85

-- ThreadDeadlockRisk: 10'dan fazla BLOCKED thread var mi?
jvm_threads_states_threads{job=~"order-service|inventory-service|payment-service", state="blocked"} > 10

-- HighGCPause: Ortalama GC duraklama suresi 500ms'den fazla mi?
(
  rate(jvm_gc_pause_seconds_sum{job=~"order-service|inventory-service|payment-service"}[5m])
  /
  rate(jvm_gc_pause_seconds_count{job=~"order-service|inventory-service|payment-service"}[5m])
) > 0.5

-- HighErrorLogRate: Error log orani saniyede 0.5'ten fazla mi?
rate(logback_events_total{job=~"order-service|inventory-service|payment-service", level="error"}[5m]) > 0.5

-- ServiceRecentlyRestarted: Servis 5 dakikadan az suredir mi calisiyor?
process_uptime_seconds{job=~"order-service|inventory-service|payment-service"} < 300

-- NoRequestsReceived: Hic istek gelmiyor mu?
rate(http_server_requests_seconds_count{job=~"order-service|inventory-service|payment-service"}[5m]) == 0
```

---

## Erişim URL'leri Özet Tablosu

| Servis | URL | Açıklama |
|--------|-----|----------|
| Order Service | http://localhost:8081 | E-ticaret sipariş servisi |
| Order Service Metrics | http://localhost:8081/actuator/prometheus | Prometheus formatında metrikler |
| Order Service Health | http://localhost:8081/actuator/health | Servis sağlığı |
| Order Service H2 Console | http://localhost:8081/h2-console | H2 veritabanı yönetimi |
| Inventory Service | http://localhost:8082 | Stok yönetim servisi |
| Inventory Service H2 Console | http://localhost:8082/h2-console | H2 veritabanı yönetimi |
| Payment Service | http://localhost:8083 | Ödeme servisi |
| Payment Service H2 Console | http://localhost:8083/h2-console | H2 veritabanı yönetimi |
| Prometheus UI | http://localhost:9090 | Metrik sorgulama ve alert görüntüleme |
| Prometheus Targets | http://localhost:9090/targets | Scrape hedefleri durumu |
| AlertManager UI | http://localhost:9093 | Alert yönetimi ve susturma |
| Grafana | http://localhost:3000 | Dashboard ve görselleştirme (admin/admin) |
