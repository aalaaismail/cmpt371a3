TestServer needs an extra statement to break out of loop

Graceful close doesn't work properly if loss rate is > 0
- If an ACK for a FIN segment from the client side gets lost, the client closes regardless and the server perpetually resends the FIN.
- This causes the server to endlessly resend the FIN segment