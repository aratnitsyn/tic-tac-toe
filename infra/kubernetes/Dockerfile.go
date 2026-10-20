# CGO_ENABLED=0 Linux binaries do not need a shell, libc or application TLS.
FROM scratch
COPY --chmod=0555 service /service
COPY --chmod=0555 bootstrap-streams /bootstrap-streams
USER 10001:10001
ENTRYPOINT ["/service"]
