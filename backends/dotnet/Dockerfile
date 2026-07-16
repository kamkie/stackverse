# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-dotnet:local -f backends/dotnet/Dockerfile .

FROM mcr.microsoft.com/dotnet/sdk:10.0.301 AS build
WORKDIR /src
# project file first so the dependency layer caches across source changes
COPY backends/dotnet/src/StackverseBackend/StackverseBackend.csproj src/StackverseBackend/
COPY backends/dotnet/src/StackverseBackend/packages.lock.json src/StackverseBackend/
RUN dotnet restore src/StackverseBackend/StackverseBackend.csproj --locked-mode
COPY backends/dotnet/src/ src/
RUN dotnet publish src/StackverseBackend/StackverseBackend.csproj --no-restore -c Release -o /app /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0.10 AS runtime
# Npgsql probes for Kerberos/GSSAPI at startup; without the library it prints a
# loader error to stderr on every boot (harmless, but noise is a defect here)
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgssapi-krb5-2 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app .
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV PORT=8080
ENV OTEL_SDK_DISABLED=true
# the app binds from PORT via UseUrls; the base image's port default would only
# produce an "Overriding HTTP_PORTS" warning on every boot
ENV ASPNETCORE_HTTP_PORTS=
EXPOSE 8080
# non-root user built into the .NET base images
USER $APP_UID
ENTRYPOINT ["dotnet", "StackverseBackend.dll"]
