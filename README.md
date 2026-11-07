# Knote con MinIO y Kubernetes

Proyecto basado en Knote de Learnk8s, modificado para utilizar almacenamiento de objetos MinIO en lugar del almacenamiento local de imágenes.

## Tecnologias

- Java 21
- Spring Boot 3.1.2
- Maven
- MongoDB 6
- MinIO AIStor
- Docker
- Kubernetes
- Minikube

## Arquitectura

La aplicacion utiliza tres componentes:

1. Knote: permite crear y consultar notas.
2. MongoDB: almacena el contenido de las notas.
3. MinIO: almacena las imagenes en el bucket image-storage.

Las imagenes se guardan de forma centralizada para que todas las replicas de Knote puedan acceder a ellas.

## Ejecucion local

Se necesita Java 21, Maven, Docker y los servicios MongoDB y MinIO disponibles.

Configurar las siguientes variables de entorno:

- MONGO_URL
- MINIO_ENDPOINT
- MINIO_ACCESS_KEY
- MINIO_SECRET_KEY
- MINIO_BUCKET

Ejecutar la aplicacion:

    mvn clean spring-boot:run

## Construccion de Docker

    mvn clean package
    docker build -t andresd01/knote:2.0.1 .
    docker push andresd01/knote:2.0.1

Imagen publicada:

https://hub.docker.com/r/andresd01/knote

## Despliegue en Kubernetes

Antes del despliegue se deben configurar los Secrets de Kubernetes para las credenciales y la licencia de MinIO AIStor.

Los manifiestos se encuentran en la carpeta kube:

- knote.yaml
- mongo.yaml
- minio.yaml

Desplegar:

    kubectl apply -f kube

Consultar los Pods:

    kubectl get pods

## Escalamiento

La aplicacion Knote fue probada con 10 replicas:

    kubectl scale deployment/knote --replicas=10

Comprobar:

    kubectl get pods -l app=knote

## Almacenamiento

MongoDB conserva las notas y MinIO almacena las imagenes mediante un bucket llamado image-storage.

El almacenamiento persistente permite conservar los datos aunque se reemplacen los Pods de las aplicaciones.

## Autor

andresd01

## Proyecto original

https://github.com/jilopezv-udem/knote
