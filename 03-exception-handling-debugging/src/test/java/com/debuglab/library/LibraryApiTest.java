package com.debuglab.library;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LibraryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void catalogueListsEverySeededBook() throws Exception {
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void borrowingAnAvailableBookDecrementsStock() throws Exception {
        mockMvc.perform(post("/api/borrow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":1,\"memberName\":\"Priya Iyer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookTitle").value("Effective Java"))
                .andExpect(jsonPath("$.availableCopies").value(3));
    }

    @Test
    void requestingAnUnknownBookReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/books/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void borrowingABookWithNoCopiesLeftReturnsConflict() throws Exception {
        mockMvc.perform(post("/api/borrow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":4,\"memberName\":\"Rohan Mehta\"}"))
                .andExpect(status().isConflict());
    }
}
