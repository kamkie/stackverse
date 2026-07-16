FROM mcr.microsoft.com/dotnet/sdk:10.0.301 AS build
WORKDIR /src
COPY src/StackverseGateway/StackverseGateway.csproj src/StackverseGateway/
COPY src/StackverseGateway/packages.lock.json src/StackverseGateway/
RUN dotnet restore src/StackverseGateway/StackverseGateway.csproj --locked-mode
COPY src/ src/
RUN dotnet publish src/StackverseGateway/StackverseGateway.csproj --no-restore -c Release -o /app /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0.10 AS runtime
WORKDIR /app
COPY --from=build /app .
ENV PORT=8000
EXPOSE 8000
# non-root user built into the .NET base images
USER $APP_UID
ENTRYPOINT ["dotnet", "StackverseGateway.dll"]
