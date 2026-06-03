package com.example.library.api;

import com.example.library.integration.AbstractIntegrationTest;
import com.example.library.model.*;
import com.example.library.repository.BookRepository;
import com.example.library.repository.BorrowRecordRepository;
import com.example.library.repository.MemberRepository;
import com.example.library.dto.BorrowRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * API TEST (End-to-End)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LibraryApiIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private BorrowRecordRepository borrowRecordRepository;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api";
        borrowRecordRepository.deleteAll();
        bookRepository.deleteAll();
        memberRepository.deleteAll();
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private Book createTestBook(String isbn, String title, String author) {
        Book book = new Book(isbn, title, author, 3, Genre.TECHNOLOGY);
        return bookRepository.save(book);
    }

    private Member createTestMember(String name, String email, MembershipType type) {
        Member member = new Member(name, email, type);
        return memberRepository.save(member);
    }

    // =========================================================================
    // EXAMPLE: Book API tests — filled in
    // =========================================================================

    @Nested
    @DisplayName("POST /api/books")
    class CreateBookApi {

        @Test
        @DisplayName("should create a book and return 201")
        void shouldCreateBook() {
            Book newBook = new Book("978-0-13-468599-1", "Clean Code", "Robert C. Martin", 3, Genre.TECHNOLOGY);

            ResponseEntity<Book> response = restTemplate.postForEntity(
                    baseUrl + "/books", newBook, Book.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Clean Code");
            assertThat(response.getBody().getAvailableCopies()).isEqualTo(3);
        }

        @Test
        @DisplayName("should return 400 when required fields are missing")
        void shouldReturn400_WhenFieldsMissing() {
            Book invalidBook = new Book(); // no required fields set

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl + "/books", invalidBook, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return 400 when duplicate ISBN")
        void shouldReturn400_WhenDuplicateIsbn() {
            createTestBook("978-0-13-468599-1", "Clean Code", "Robert C. Martin");

            Book duplicate = new Book("978-0-13-468599-1", "Another Book", "Another Author", 2, Genre.FICTION);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl + "/books", duplicate, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /api/books")
    class GetBooksApi {

        @Test
        @DisplayName("should return all books")
        void shouldReturnAllBooks() {
            createTestBook("978-1", "Book A", "Author A");
            createTestBook("978-2", "Book B", "Author B");

            ResponseEntity<Book[]> response = restTemplate.getForEntity(
                    baseUrl + "/books", Book[].class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("should return 404 for non-existent book")
        void shouldReturn404_WhenBookNotFound() {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    baseUrl + "/books/999", Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =========================================================================
    // EXAMPLE: Borrow flow — the most important E2E test
    // =========================================================================

    @Nested
    @DisplayName("Borrow Flow (POST /api/borrows)")
    class BorrowFlowApi {

        @Test
        @DisplayName("should complete full borrow-return cycle")
        void shouldCompleteBorrowReturnCycle() {
            // Setup
            Book book = createTestBook("978-1", "Test Book", "Test Author");
            Member member = createTestMember("Alice", "alice@test.com", MembershipType.STANDARD);

            // 1. Borrow the book
            BorrowRequest borrowRequest = new BorrowRequest(book.getId(), member.getId());
            ResponseEntity<Map> borrowResponse = restTemplate.postForEntity(
                    baseUrl + "/borrows", borrowRequest, Map.class);

            assertThat(borrowResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(borrowResponse.getBody()).containsEntry("bookTitle", "Test Book");
            assertThat(borrowResponse.getBody()).containsEntry("memberName", "Alice");
            assertThat(borrowResponse.getBody()).containsEntry("status", "BORROWED");

            Number borrowId = (Number) borrowResponse.getBody().get("id");

            // 2. Verify book availability decreased
            ResponseEntity<Book> bookResponse = restTemplate.getForEntity(
                    baseUrl + "/books/" + book.getId(), Book.class);
            assertThat(bookResponse.getBody().getAvailableCopies()).isEqualTo(2);

            // 3. Return the book
            ResponseEntity<Map> returnResponse = restTemplate.postForEntity(
                    baseUrl + "/borrows/" + borrowId.longValue() + "/return",
                    null, Map.class);

            assertThat(returnResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(returnResponse.getBody()).containsEntry("status", "RETURNED");

            // 4. Verify book availability increased back
            bookResponse = restTemplate.getForEntity(
                    baseUrl + "/books/" + book.getId(), Book.class);
            assertThat(bookResponse.getBody().getAvailableCopies()).isEqualTo(3);
        }
    }

    // =========================================================================
    // TODO: Students should write these API tests
    // =========================================================================

    @Nested
    @DisplayName("POST /api/borrows - Error cases")
    class BorrowErrorsApi {

        @Test
        @DisplayName("returns 409 when a student exceeds the borrow limit")
        void studentCannotBorrowMoreThanAllowedLimit() {
            Member studentMember = createTestMember("Berke", "berke@test.com", MembershipType.STUDENT);
            Book firstBook = createTestBook("978-limit-1", "Intro to Java", "Author A");
            Book secondBook = createTestBook("978-limit-2", "Database Basics", "Author B");
            Book extraBook = createTestBook("978-limit-3", "Clean Architecture", "Author C");

            restTemplate.postForEntity(baseUrl + "/borrows",
                    new BorrowRequest(firstBook.getId(), studentMember.getId()), Map.class);
            restTemplate.postForEntity(baseUrl + "/borrows",
                    new BorrowRequest(secondBook.getId(), studentMember.getId()), Map.class);

            ResponseEntity<Map> thirdBorrowResponse = restTemplate.postForEntity(baseUrl + "/borrows",
                    new BorrowRequest(extraBook.getId(), studentMember.getId()), Map.class);

            assertThat(thirdBorrowResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(thirdBorrowResponse.getBody()).isNotNull();
        }

        @Test
        @DisplayName("returns 409 when the requested book has no available copies")
        void cannotBorrowBookWithNoAvailableCopies() {
            Book unavailableBook = createTestBook("978-empty-1", "Refactoring", "Martin Fowler");
            unavailableBook.setAvailableCopies(0);
            bookRepository.save(unavailableBook);

            Member regularMember = createTestMember("Mert", "mert@test.com", MembershipType.STANDARD);

            ResponseEntity<Map> borrowResponse = restTemplate.postForEntity(baseUrl + "/borrows",
                    new BorrowRequest(unavailableBook.getId(), regularMember.getId()), Map.class);

            assertThat(borrowResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(borrowResponse.getBody()).isNotNull();
        }

        @Test
        @DisplayName("returns 404 when the member id does not exist")
        void borrowingFailsForUnknownMember() {
            Book availableBook = createTestBook("978-member-missing", "Effective Java", "Joshua Bloch");
            long missingMemberId = 9999L;

            ResponseEntity<Map> borrowResponse = restTemplate.postForEntity(baseUrl + "/borrows",
                    new BorrowRequest(availableBook.getId(), missingMemberId), Map.class);

            assertThat(borrowResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(borrowResponse.getBody()).isNotNull();
        }

        @Test
        @DisplayName("returns 404 when the book id does not exist")
        void borrowingFailsForUnknownBook() {
            Member regularMember = createTestMember("Deniz", "deniz@test.com", MembershipType.STANDARD);
            long missingBookId = 9999L;

            ResponseEntity<Map> borrowResponse = restTemplate.postForEntity(baseUrl + "/borrows",
                    new BorrowRequest(missingBookId, regularMember.getId()), Map.class);

            assertThat(borrowResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(borrowResponse.getBody()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Member API")
    class MemberApiTests {

        @Test
        @DisplayName("should create a new member")
        void createMemberTest() {

            Member memberToCreate =
                    new Member("Alice", "alice@example.com", MembershipType.STANDARD);

            ResponseEntity<Member> response = restTemplate.postForEntity(
                    baseUrl + "/members",
                    memberToCreate,
                    Member.class);

            Member createdMember = response.getBody();

            assertThat(createdMember).isNotNull();
            assertThat(createdMember.getId()).isNotNull();
            assertThat(createdMember.getName()).isEqualTo("Alice");
            assertThat(createdMember.isActive()).isTrue();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("should deactivate member")
        void deleteMemberTest() {

            Member existingMember =
                    createTestMember("Alice", "alice@test.com", MembershipType.STANDARD);

            restTemplate.delete(baseUrl + "/members/" + existingMember.getId());

            ResponseEntity<Member> response = restTemplate.getForEntity(
                    baseUrl + "/members/" + existingMember.getId(),
                    Member.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();

            assertThat(response.getBody().isActive()).isFalse();
        }

        @Test
        @DisplayName("should reject invalid email")
        void invalidEmailShouldReturnBadRequest() {

            Member invalidMember =
                    new Member("Alice", "not-an-email", MembershipType.STANDARD);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl + "/members",
                    invalidMember,
                    Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Search & Filter API")
    class SearchApiTests {

        @Test
        @DisplayName("should search books by keyword via GET /api/books/search?keyword=...")
        void shouldSearchBooks() {
            // Arrange: create 2 books matching keyword and 1 that does not
            createTestBook("978-1", "Clean Code", "Robert C. Martin");
            createTestBook("978-2", "Clean Architecture", "Robert C. Martin");
            createTestBook("978-3", "Refactoring", "Martin Fowler");

            // Act: search with keyword "clean" (case-insensitive handled by service)
            ResponseEntity<Book[]> response = restTemplate.getForEntity(
                    baseUrl + "/books/search?keyword=clean", Book[].class);

            // Assert: only the two "Clean *" books are returned
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody())
                    .extracting(Book::getTitle)
                    .containsExactlyInAnyOrder("Clean Code", "Clean Architecture");
        }

        @Test
        @DisplayName("should get active borrows for a member")
        void shouldGetActiveBorrows() {
            // Arrange: one member, two books
            Member member = createTestMember("Alice", "alice@test.com", MembershipType.STANDARD);
            Book book1 = createTestBook("978-1", "Book One", "Author A");
            Book book2 = createTestBook("978-2", "Book Two", "Author B");

            // Borrow both books
            BorrowRequest request1 = new BorrowRequest(book1.getId(), member.getId());
            ResponseEntity<Map> borrow1Response = restTemplate.postForEntity(
                    baseUrl + "/borrows", request1, Map.class);
            assertThat(borrow1Response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Number borrow1Id = (Number) borrow1Response.getBody().get("id");

            BorrowRequest request2 = new BorrowRequest(book2.getId(), member.getId());
            ResponseEntity<Map> borrow2Response = restTemplate.postForEntity(
                    baseUrl + "/borrows", request2, Map.class);
            assertThat(borrow2Response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // Return the first book
            ResponseEntity<Map> returnResponse = restTemplate.postForEntity(
                    baseUrl + "/borrows/" + borrow1Id.longValue() + "/return",
                    null, Map.class);
            assertThat(returnResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Act: get active borrows for this member
            ResponseEntity<Map[]> activeResponse = restTemplate.getForEntity(
                    baseUrl + "/borrows/member/" + member.getId() + "/active", Map[].class);

            // Assert: only 1 active borrow remains (book2)
            assertThat(activeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(activeResponse.getBody()).hasSize(1);
            assertThat(activeResponse.getBody()[0]).containsEntry("bookTitle", "Book Two");
            assertThat(activeResponse.getBody()[0]).containsEntry("status", "BORROWED");
        }
    }
}