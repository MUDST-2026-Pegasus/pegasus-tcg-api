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
├── examples/
│   └── secret.example.yaml     ตัวอย่าง Secret (ค่า placeholder)
└── dependencies/               ใช้กับคลัสเตอร์ในเครื่องเท่านั้น
    ├── postgres.yaml           Postgres 17 แบบ StatefulSet + PVC
    └── minio.yaml              MinIO แบบ StatefulSet + PVC
```

Backend ต้องมี Postgres และ MinIO จึงจะขึ้น Ready ได้ ไฟล์ใน `dependencies/` มีไว้ให้รันครบทั้งชุดบนเครื่องตัวเอง
เป็น replica เดียว ไม่มี backup ถ้ามีฐานข้อมูลและ object storage อยู่แล้ว ให้ข้ามโฟลเดอร์นี้
แล้วแก้ `DB_URL` กับ `MINIO_ENDPOINT` ใน `configmap.yaml` ให้ชี้ไปที่ของจริง

---

## ขั้นตอน Deploy

รันทุกคำสั่งจาก root ของ repo `pegasus-tcg-api`

### 1. Build image

```bash
docker build -t pegasus-tcg-api:latest .
```

Deployment ใช้ `imagePullPolicy: IfNotPresent` จึงหยิบ image ในเครื่องได้เลยบน Docker Desktop
ถ้าใช้ kind หรือ minikube ต้อง load image เข้าคลัสเตอร์ก่อน (`kind load docker-image` หรือ `minikube image load`)

### 2. สร้าง Secret

```bash
cp k8s/examples/secret.example.yaml k8s/secret.yaml
```

แก้ค่าใน `k8s/secret.yaml` ให้เป็นค่าจริง โดยเฉพาะ `JWT_SECRET` (สร้างด้วย `openssl rand -base64 48`)
ไฟล์นี้อยู่ใน `.gitignore` แล้ว ห้าม commit

### 3. Apply

```bash
kubectl apply -f k8s/dependencies/ -f k8s/
```

`kubectl apply -f` ไม่ลงไปในโฟลเดอร์ย่อย ไฟล์ตัวอย่างใน `examples/` จึงไม่ถูก apply ไปทับ Secret จริง

### 4. ตรวจสอบสถานะ

```bash
kubectl rollout status statefulset/pegasus-postgres
kubectl rollout status statefulset/pegasus-minio
kubectl rollout status deployment/pegasus-backend-deployment

kubectl get pods -l app.kubernetes.io/part-of=pegasus-tcg
kubectl get service pegasus-backend-service
kubectl get pdb pegasus-backend-pdb
```

ถ้า backend ขึ้นก่อน Postgres พร้อม Pod จะ restart เองจนต่อฐานข้อมูลได้ ไม่ต้องทำอะไรเพิ่ม

### 5. เรียกใช้งานผ่าน Ingress

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

### 6. เรียกใช้งานผ่าน Port-Forward (ไม่ผ่าน Ingress)

```bash
kubectl port-forward service/pegasus-backend-service 8080:8080
```

```bash
curl http://localhost:8080/actuator/health
```

Swagger UI อยู่ที่ <http://localhost:8080/swagger-ui.html>

ถ้าจะทดสอบอัปโหลดรูปจาก browser ต้องเปิดทางให้ MinIO ด้วย เพราะ presigned URL ชี้ไปที่ `http://localhost:9000`

```bash
kubectl port-forward service/pegasus-minio-service 9000:9000
```

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
kubectl delete -f k8s/ -f k8s/dependencies/
```

PVC ของ StatefulSet ไม่ถูกลบตาม ข้อมูลใน Postgres และ MinIO จะยังอยู่ ถ้าต้องการล้างข้อมูลด้วย

```bash
kubectl delete pvc data-pegasus-postgres-0 data-pegasus-minio-0
```

---

## เหตุผลของค่าที่ตั้งไว้

| คุณสมบัติ | ค่าที่ใช้ | เหตุผล |
| :--- | :--- | :--- |
| **Replicas** | 3 | รันหลายตัวได้เพราะ scheduled job ทุกตัวกันรันซ้ำด้วย Postgres advisory lock และ Flyway ล็อกระหว่าง migrate |
| **RollingUpdate** | `maxSurge: 1`, `maxUnavailable: 0` | Pod เดิมไม่ถูกลดจนกว่าตัวใหม่จะ Ready จึงไม่มีช่วงที่ให้บริการไม่ได้ |
| **startupProbe** | สูงสุด 150 วินาที | JVM และ Flyway migration บูตช้า ถ้าใช้แค่ liveness Pod จะถูก restart ก่อนบูตเสร็จ |
| **liveness / readiness** | `/actuator/health/liveness`, `/actuator/health/readiness` | แยกกันเพื่อให้ "ค้างต้อง restart" กับ "ยังไม่พร้อมรับ traffic" เป็นคนละเรื่อง |
| **Resources** | request 250m / 512Mi, limit 1 CPU / 1Gi | heap ถูกจำกัดที่ 75% ของ memory limit ด้วย `MaxRAMPercentage` กัน container โดน OOMKill |
| **ConfigMap / Secret** | แยกกัน | config ทั่วไปอยู่ใน git ได้ ส่วนรหัสผ่านและ key ไม่เข้า repo |
| **runAsUser** | 100:101 | user `spring` ใน image เป็นชื่อ `runAsNonRoot` ตรวจได้เฉพาะ UID ที่เป็นตัวเลข |
| **readOnlyRootFilesystem** | เปิด พร้อม `emptyDir` ที่ `/tmp` | แอปเขียนไฟล์ได้เฉพาะ `/tmp` ที่ Tomcat ต้องใช้ |
| **Capabilities / Seccomp** | drop `ALL`, `RuntimeDefault` | backend ไม่ต้องใช้สิทธิ์พิเศษของ Linux |
| **ServiceAccount token** | ไม่ mount | backend ไม่ได้เรียก Kubernetes API |
| **Pod Anti-Affinity** | preferred | กระจาย Pod ไปคนละ Node ถ้าทำได้ แต่ยังรันบนคลัสเตอร์ Node เดียวได้ |
| **PodDisruptionBudget** | `minAvailable: 2` | ตอน drain Node ต้องเหลือ backend อย่างน้อย 2 ตัว |
| **Graceful shutdown** | `preStop` 5 วินาที + รอ request 30 วินาที | ให้ Service ถอด Pod ออกก่อน แล้วรอ request ที่ค้างให้จบ |
| **Ingress** | `/pegasus-backend-service` พร้อม rewrite | หลาย service ใช้ `localhost` ร่วมกันได้โดยแยกด้วย path และแอปไม่ต้องรู้จัก prefix |
| **Forwarded headers** | `SERVER_FORWARD_HEADERS_STRATEGY: framework` | ให้ redirect และ Swagger UI สร้างลิงก์ที่มี prefix ของ Ingress |
| **Postgres / MinIO** | StatefulSet + headless Service + `volumeClaimTemplates` | ชื่อ Pod และ volume คงที่ ข้อมูลอยู่รอดเมื่อ Pod ถูกสร้างใหม่ |

---

## ข้อจำกัด

* `PAYMENT_MOCK_ENABLED` ถูกตั้งเป็น `false` ถ้าจะ demo การจ่ายเงินโดยไม่มี payment gateway ต้องแก้เป็น `true` ใน `configmap.yaml`
* `CORS_ALLOWED_ORIGINS` ตั้งไว้ที่ `http://localhost:8081` ตามพอร์ตที่ frontend ใช้ port-forward ถ้า frontend อยู่ที่อื่นต้องแก้ตาม
* เมื่อเข้าผ่านพอร์ต 16969 เอกสาร OpenAPI จะระบุ server เป็น `http://localhost/pegasus-backend-service` (ไม่มีพอร์ต) ปุ่ม Try it out ใน Swagger UI จึงยิงไปที่พอร์ต 80 การเรียก API ตรงๆ ผ่านพอร์ต 16969 ใช้ได้ปกติ
* Ingress เป็น HTTP อย่างเดียว ยังไม่ได้ตั้ง TLS
* Manifest ใช้ namespace ปัจจุบันของ `kubectl` ไม่ได้กำหนด namespace ไว้ในไฟล์
