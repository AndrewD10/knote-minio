package io.learnk8s.knote;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SpringBootApplication
@EnableConfigurationProperties(MinioProperties.class)
public class KnoteApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnoteApplication.class, args);
    }

    @Bean
    MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}

interface NotesRepository extends MongoRepository<Note, String> {
}

@Document(collection = "notes")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
class Note {
    @Id
    private String id;
    private String description;

    @Override
    public String toString() {
        return description;
    }
}

@ConfigurationProperties(prefix = "minio")
class MinioProperties {
    private String endpoint = "http://localhost:9000";
    private String accessKey = "mykey";
    private String secretKey = "mysecret";
    private String bucket = "image-storage";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}

@Controller
class KNoteController {

    private final NotesRepository notesRepository;
    private final MinioClient minioClient;
    private final MinioProperties properties;

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    @Autowired
    KNoteController(NotesRepository notesRepository,
                    MinioClient minioClient,
                    MinioProperties properties) {
        this.notesRepository = notesRepository;
        this.minioClient = minioClient;
        this.properties = properties;
    }

    // Espera a que MinIO esté disponible y crea el bucket si todavía no existe.
    @PostConstruct
    public void initMinio() {
        while (true) {
            try {
                boolean bucketExists = minioClient.bucketExists(
                        BucketExistsArgs.builder()
                                .bucket(properties.getBucket())
                                .build());

                if (!bucketExists) {
                    minioClient.makeBucket(
                            MakeBucketArgs.builder()
                                    .bucket(properties.getBucket())
                                    .build());
                    System.out.println("Bucket de MinIO creado: " + properties.getBucket());
                } else {
                    System.out.println("Bucket de MinIO listo: " + properties.getBucket());
                }
                return;
            } catch (Exception e) {
                System.out.println("MinIO todavía no está disponible: " + e.getMessage());
                System.out.println("Se intentará conectar nuevamente en 5 segundos.");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Se interrumpió la conexión con MinIO.", interruptedException);
                }
            }
        }
    }

    @GetMapping("/")
    public String index(Model model) {
        getAllNotes(model);
        return "index";
    }

    @PostMapping("/note")
    public String saveNotes(@RequestParam("image") MultipartFile file,
                            @RequestParam String description,
                            @RequestParam(required = false) String publish,
                            @RequestParam(required = false) String upload,
                            Model model) throws Exception {

        if (publish != null && publish.equals("Publish")) {
            saveNote(description, model);
            getAllNotes(model);
            return "redirect:/";
        }

        if (upload != null && upload.equals("Upload")) {
            if (file != null && !file.isEmpty()) {
                uploadImage(file, description, model);
            } else {
                model.addAttribute("description", description);
            }
            getAllNotes(model);
            return "index";
        }

        getAllNotes(model);
        return "index";
    }

    private void getAllNotes(Model model) {
        List<Note> notes = notesRepository.findAll();
        Collections.reverse(notes);
        model.addAttribute("notes", notes);
    }

    // Guarda la imagen en MinIO y deja en la nota una referencia a /img/.
    private void uploadImage(MultipartFile file, String description, Model model) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String extension = "";

        if (originalFilename != null) {
            int dotIndex = originalFilename.lastIndexOf('.');
            if (dotIndex >= 0) {
                String candidate = originalFilename.substring(dotIndex).toLowerCase(Locale.ROOT);
                if (candidate.matches("\\.[a-z0-9]{1,8}")) {
                    extension = candidate;
                }
            }
        }

        String fileId = UUID.randomUUID() + extension;
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(fileId)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(contentType)
                            .build());
        }

        String text = description == null ? "" : description;
        if (!text.isBlank()) {
            text += "\n\n";
        }
        text += "![](/img/" + fileId + ")";
        model.addAttribute("description", text);
    }

    // Recupera la imagen desde MinIO; ninguna réplica depende de su disco local.
    @GetMapping("/img/{name}")
    @ResponseBody
    public ResponseEntity<byte[]> getImageByName(@PathVariable String name) throws Exception {
        StatObjectResponse objectInfo = minioClient.statObject(
                StatObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(name)
                        .build());

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (objectInfo.contentType() != null && !objectInfo.contentType().isBlank()) {
            mediaType = MediaType.parseMediaType(objectInfo.contentType());
        }

        try (InputStream imageStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(name)
                        .build())) {
            byte[] imageBytes = StreamUtils.copyToByteArray(imageStream);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(imageBytes);
        }
    }

    private void saveNote(String description, Model model) {
        if (description != null && !description.trim().isEmpty()) {
            Node document = parser.parse(description.trim());
            String html = renderer.render(document);
            notesRepository.save(new Note(null, html));
            model.addAttribute("description", "");
        }
    }
}
