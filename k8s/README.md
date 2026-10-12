# Pegasus Backend - Kubernetes Manifests

ชุด Manifest สำหรับ Deploy ระบบ Backend (`pegasus-tcg-api`) ซึ่งเป็น Spring Boot บน Java 25 ขึ้น Kubernetes
คู่กับ Manifest ของ Frontend ใน repo `pegasus-tcg-web`

---

## โครงสร้างไฟล์

```
k8s/
├── configmap.yaml              ค่า config ที่ไม่เป็นความลับ
├── deployment.yaml             Deployment ของ backend 3 replicas
├── service.yaml                ClusterIP Service พอร์ต 8080
├── ingress.yaml                Ingress เส้นทาง /pegasus-backend-service
├── pdb.yaml                    PodDisruptionBudget
└── examples/
    └── secret.example.yaml     ตัวอย่าง Secret (ค่า placeholder)
```

ในคลัสเตอร์มีเฉพาะ backend เท่านั้น **Postgres และ MinIO รันนอกคลัสเตอร์** ด้วย `docker-compose.yaml` ของ repo นี้
backend ต่อออกไปหาทั้งสองตัวผ่านชื่อ `host.docker.internal` ซึ่งหมายถึงเครื่องที่รัน Docker Desktop
ที่อยู่และรหัสของทั้งสองตัวอยู่ใน Secret เพราะต่างกันไปตามเครื่องของแต่ละคน

---

## ขั้นตอน Deploy

รันทุกคำสั่งจาก root ของ repo `pegasus-tcg-api`

### 1. Build image

```bash
docker build -t pegasus-tcg-api:latest .
```

Deployment ใช้ `imagePullPolicy: IfNotPresent` จึงหยิบ image ในเครื่องได้เลยบน Docker Desktop
ถ้าใช้ kind หรือ minikube ต้อง load image เข้าคลัสเตอร์ก่อน (`kind load docker-image` หรือ `minikube image load`)

### 2. เปิด Postgres และ MinIO

ต้องมีไฟล์ `.env` ก่อน (สร้างด้วย `make env` แล้วแก้ค่าตาม README หลัก)

```bash
docker compose up -d --wait postgres minio
```

ต้องเปิดก่อน backend เสมอ ถ้า backend ต่อฐานข้อมูลไม่ได้ตอนบูต Pod จะ restart วนจนกว่าฐานข้อมูลจะพร้อม

### 3. สร้าง Secret

```bash
cp k8s/examples/secret.example.yaml k8s/secret.yaml
```

แก้ค่าใน `k8s/secret.yaml` ให้ตรงกับ `.env` ของเครื่องตัวเอง ไฟล์นี้อยู่ใน `.gitignore` แล้ว ห้าม commit

| ค่าใน `k8s/secret.yaml` | ต้องตรงกับค่าใน `.env` |
| :--- | :--- |
| พอร์ตใน `DB_URL` | `DB_PORT` |
| `DB_USERNAME`, `DB_PASSWORD` | `POSTGRES_USER`, `POSTGRES_PASSWORD` |
| พอร์ตใน `MINIO_ENDPOINT` และ `MINIO_PUBLIC_ENDPOINT` | `MINIO_PORT` |
| `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` |
| `JWT_SECRET` | สร้างใหม่ด้วย `openssl rand -base64 48` |

### 4. Apply

```bash
kubectl apply -f k8s/
```

`kubectl apply -f` ไม่ลงไปในโฟลเดอร์ย่อย ไฟล์ตัวอย่างใน `examples/` จึงไม่ถูก apply ไปทับ Secret จริง

### 5. ตรวจสอบสถานะ

```bash
kubectl rollout status deployment/pegasus-backend-deployment

kubectl get pods -l app=pegasus-backend
kubectl get service pegasus-backend-service
kubectl get ingress pegasus-backend-ingress
kubectl get pdb pegasus-backend-pdb
```

### 6. เรียกใช้งานผ่าน Ingress

`ingress.yaml` เปิดให้เข้า backend ที่ `http://localhost/pegasus-backend-service` โดยตัด prefix ออกก่อนส่งต่อ
แอปจึงเห็น path เดิม เช่น `/pegasus-backend-service/api/v1/games` ไปถึงแอปเป็น `/api/v1/games`

Ingress ต้องมี Ingress Controller ในคลัสเตอร์ก่อนจึงจะทำงาน ติดตั้งครั้งเดียวต่อคลัสเตอร์

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/cloud/deploy.yaml
```

```bash
kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx
```

จากนั้นเรียกได้เลย

```bash
curl http://localhost/pegasus-backend-service/actuator/health
```

Swagger UI อยู่ที่ <http://localhost/pegasus-backend-service/swagger-ui.html>

ถ้าพอร์ต 80 ของเครื่องถูกใช้อยู่ ให้เปิดทางเข้า Ingress Controller ที่พอร์ต 16969 แทน

```bash
kubectl port-forward -n ingress-nginx service/ingress-nginx-controller 16969:80
```

```bash
curl http://localhost:16969/pegasus-backend-service/actuator/health
```

> โครงการ ingress-nginx ถูกเก็บเข้า archive เมื่อมีนาคม 2026 และไม่มี security patch แล้ว
> ใช้กับคลัสเตอร์ในเครื่องเพื่อการเรียนได้ แต่ไม่ควรนำไปใช้กับระบบจริง
> annotation ใน `ingress.yaml` เป็นของ ingress-nginx ถ้าเปลี่ยน controller ต้องแก้ส่วน rewrite ตาม

### 7. เรียกใช้งานผ่าน Port-Forward (ไม่ผ่าน Ingress)

```bash
kubectl port-forward service/pegasus-backend-service 8080:8080
```

```bash
curl http://localhost:8080/actuator/health
```

Swagger UI อยู่ที่ <http://localhost:8080/swagger-ui.html>

---

## Rollout และ Rollback

```bash
# ดูสถานะและประวัติ revision
kubectl rollout status deployment/pegasus-backend-deployment
kubectl rollout history deployment/pegasus-backend-deployment

# แก้ ConfigMap หรือ Secret แล้ว ต้อง restart ให้ Pod อ่านค่าใหม่
kubectl rollout restart deployment/pegasus-backend-deployment

# ย้อนกลับไป revision ก่อนหน้า
kubectl rollout undo deployment/pegasus-backend-deployment
```

### ใช้ image จาก GHCR แทน image ในเครื่อง

CI push image ขึ้น `ghcr.io/mudst-2026-pegasus/pegasus-tcg-api` ทุกครั้งที่ merge เข้า `main`
ให้ใช้ tag แบบ `sha-<commit>` แทน `latest` เพื่อให้รู้ว่ารันเวอร์ชันไหนและ rollback ได้แน่นอน

```bash
kubectl set image deployment/pegasus-backend-deployment pegasus-backend=ghcr.io/mudst-2026-pegasus/pegasus-tcg-api:sha-<commit>
```

package บน GHCR เป็น private โดย default ถ้ายังไม่ได้เปิด public ต้องสร้าง `imagePullSecret` ให้คลัสเตอร์ก่อน

---

## ลบทิ้ง

```bash
kubectl delete -f k8s/
```

Postgres และ MinIO ไม่ได้อยู่ในคลัสเตอร์ จึงไม่ถูกกระทบ ถ้าจะหยุดด้วยให้สั่ง

```bash
docker compose stop postgres minio
```

ระวัง `docker compose down -v` เพราะจะลบ volume ที่เก็บข้อมูลของทั้งสองตัวไปด้วย

---

## เหตุผลของค่าที่ตั้งไว้

| คุณสมบัติ | ค่าที่ใช้ | เหตุผล |
| :--- | :--- | :--- |
| **Replicas** | 3 | รันหลายตัวได้เพราะ scheduled job ทุกตัวกันรันซ้ำด้วย Postgres advisory lock และ Flyway ล็อกระหว่าง migrate |
| **RollingUpdate** | `maxSurge: 1`, `maxUnavailable: 0` | Pod เดิมไม่ถูกลดจนกว่าตัวใหม่จะ Ready จึงไม่มีช่วงที่ให้บริการไม่ได้ |
| **startupProbe** | สูงสุด 150 วินาที | JVM และ Flyway migration บูตช้า ถ้าใช้แค่ liveness Pod จะถูก restart ก่อนบูตเสร็จ |
| **liveness / readiness** | `/actuator/health/liveness`, `/actuator/health/readiness` | แยกกันเพื่อให้ "ค้างต้อง restart" กับ "ยังไม่พร้อมรับ traffic" เป็นคนละเรื่อง |
| **Resources** | request 250m / 512Mi, limit 1 CPU / 1Gi | heap ถูกจำกัดที่ 75% ของ memory limit ด้วย `MaxRAMPercentage` กัน container โดน OOMKill |
| **ConfigMap / Secret** | แยกกัน | config ทั่วไปอยู่ใน git ได้ ส่วนรหัสผ่าน key และที่อยู่ของบริการนอกคลัสเตอร์ไม่เข้า repo |
| **Postgres / MinIO** | รันนอกคลัสเตอร์ด้วย docker compose | ข้อมูลไม่ผูกกับอายุของคลัสเตอร์ และใช้ฐานข้อมูลกับรูปชุดเดียวกับตอนพัฒนาบนเครื่อง |
| **runAsUser** | 100:101 | user `spring` ใน image เป็นชื่อ `runAsNonRoot` ตรวจได้เฉพาะ UID ที่เป็นตัวเลข |
| **readOnlyRootFilesystem** | เปิด พร้อม `emptyDir` ที่ `/tmp` | แอปเขียนไฟล์ได้เฉพาะ `/tmp` ที่ Tomcat ต้องใช้ |
| **Capabilities / Seccomp** | drop `ALL`, `RuntimeDefault` | backend ไม่ต้องใช้สิทธิ์พิเศษของ Linux |
| **ServiceAccount token** | ไม่ mount | backend ไม่ได้เรียก Kubernetes API |
| **Pod Anti-Affinity** | preferred | กระจาย Pod ไปคนละ Node ถ้าทำได้ แต่ยังรันบนคลัสเตอร์ Node เดียวได้ |
| **PodDisruptionBudget** | `minAvailable: 2` | ตอน drain Node ต้องเหลือ backend อย่างน้อย 2 ตัว |
| **Graceful shutdown** | `preStop` 5 วินาที + รอ request 30 วินาที | ให้ Service ถอด Pod ออกก่อน แล้วรอ request ที่ค้างให้จบ |
| **Ingress** | `/pegasus-backend-service` พร้อม rewrite | หลาย service ใช้ `localhost` ร่วมกันได้โดยแยกด้วย path และแอปไม่ต้องรู้จัก prefix |
| **Forwarded headers** | `SERVER_FORWARD_HEADERS_STRATEGY: framework` | ให้ redirect และ Swagger UI สร้างลิงก์ที่มี prefix ของ Ingress |

---

## ข้อจำกัด

* backend ในคลัสเตอร์ใช้ฐานข้อมูลและ MinIO ชุดเดียวกับตอนพัฒนาบนเครื่อง Flyway จะ migrate และ scheduled job จะทำงานกับข้อมูลชุดนั้นจริง
* `host.docker.internal` และ `kubernetes.docker.internal` มีเฉพาะบน Docker Desktop ถ้าใช้คลัสเตอร์อื่นต้องเปลี่ยนเป็นที่อยู่จริงของ Postgres และ MinIO ใน `k8s/secret.yaml`
* `MINIO_PUBLIC_ENDPOINT` ใช้ `localhost` ไม่ได้ เพราะ backend ต้องต่อ host นี้ได้เองตอน sign URL ของรูป ถ้าตั้งผิด API ที่คืนรูปจะตอบ 503
* `PAYMENT_MOCK_ENABLED` ถูกตั้งเป็น `false` ถ้าจะ demo การจ่ายเงินโดยไม่มี payment gateway ต้องแก้เป็น `true` ใน `configmap.yaml`
* `CORS_ALLOWED_ORIGINS` ตั้งไว้ที่ `http://localhost:8081` ตามพอร์ตที่ frontend ใช้ port-forward ถ้า frontend อยู่ที่อื่นต้องแก้ตาม
* เมื่อเข้าผ่านพอร์ต 16969 เอกสาร OpenAPI จะระบุ server เป็น `http://localhost/pegasus-backend-service` (ไม่มีพอร์ต) ปุ่ม Try it out ใน Swagger UI จึงยิงไปที่พอร์ต 80 การเรียก API ตรงๆ ผ่านพอร์ต 16969 ใช้ได้ปกติ
* Ingress เป็น HTTP อย่างเดียว ยังไม่ได้ตั้ง TLS
* Manifest ใช้ namespace ปัจจุบันของ `kubectl` ไม่ได้กำหนด namespace ไว้ในไฟล์
