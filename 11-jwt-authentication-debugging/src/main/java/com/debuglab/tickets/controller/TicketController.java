package com.debuglab.tickets.controller;

import com.debuglab.tickets.dto.CreateTicketRequest;
import com.debuglab.tickets.entity.Ticket;
import com.debuglab.tickets.repository.TicketRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TicketController {

    private final TicketRepository ticketRepository;

    public TicketController(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<Ticket>> myTickets(Authentication authentication) {
        return ResponseEntity.ok(ticketRepository.findByReportedBy(authentication.getName()));
    }

    @PostMapping("/tickets")
    public ResponseEntity<Ticket> raise(@Valid @RequestBody CreateTicketRequest request,
                                        Authentication authentication) {
        UserDetails principal = (UserDetails) authentication.getPrincipal();

        Ticket ticket = new Ticket();
        ticket.setSubject(request.getSubject());
        ticket.setBody(request.getBody());
        ticket.setStatus("OPEN");
        ticket.setPriority(request.getPriority() == null ? "NORMAL" : request.getPriority());
        ticket.setReportedBy(principal.getUsername());
        ticket.setCreatedAt(LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CREATED).body(ticketRepository.save(ticket));
    }

    @GetMapping("/admin/tickets")
    public ResponseEntity<List<Ticket>> allTickets() {
        return ResponseEntity.ok(ticketRepository.findAll());
    }
}
