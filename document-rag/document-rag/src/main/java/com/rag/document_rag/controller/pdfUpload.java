package com.rag.document_rag.controller;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class pdfUpload {

    private final VectorStore vectorStore;

    public pdfUpload(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public String uploadPdf(
            @RequestPart("file") MultipartFile file) {

        try {

            if (file.isEmpty()) {
                return "Please select a PDF file.";
            }

         
            if (file.getContentType() == null ||
                    !file.getContentType().equals("application/pdf")) {

                return "Please upload a PDF file.";
            }

            InputStreamResource resource = new InputStreamResource(file.getInputStream());

            
            PagePdfDocumentReader pdfReader =new PagePdfDocumentReader(resource);

            List<Document> documents = pdfReader.read();

           
            TokenTextSplitter splitter = TokenTextSplitter.builder().build();

            List<Document> chunks =splitter.split(documents);

            
            vectorStore.add(chunks);

            return "Successfully uploaded and stored "
                    + chunks.size()
                    + " chunks from "
                    + file.getOriginalFilename();

        } catch (Exception e) {

            return "Error processing PDF: "
                    + e.getMessage();
        }
    }
}