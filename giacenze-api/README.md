# giacenze-api

Il contratto gRPC del servizio **Giacenze**, che Magazzino offre a Produzione:
[`src/main/proto/giacenze.proto`](src/main/proto/giacenze.proto).

È un modulo Maven condiviso. A ogni build il `protobuf-maven-plugin` scarica
`protoc` e il plugin `protoc-gen-grpc-java` da Maven Central e genera le classi
Java: i messaggi (`RiservaLottoRequest`...), lo stub client (`GiacenzeGrpc.newBlockingStub`)
e la classe base del server (`GiacenzeGrpc.GiacenzeImplBase`). Magazzino e
Produzione dipendono dal jar `it.ginvault:giacenze-api:0.2.0`, quindi un campo
cambiato si vede in compilazione da entrambe le parti.

```powershell
# Da installare nel repository Maven locale prima di compilare magazzino e produzione
mvn -f giacenze-api/pom.xml install
```

I Dockerfile di magazzino e produzione lo installano da soli: lo ricevono come
secondo build context con nome (`--build-context giacenze-api=giacenze-api`,
in Compose `additional_contexts`).

Il servizio, gli errori e le scelte (kg come stringhe decimali, JWT nel
metadata, deadline di 2 secondi) sono spiegati in [docs/grpc.md](../docs/grpc.md).
