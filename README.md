# Assign 2: Distributed and Partition Key-Value Store

## Folders and their meaning:

* [src](./src): Code used
    * [Client](./src/Client): Where the client executable is stored.
    * [FileHandler](./src/FileHandler): Thread resposible for everything related to Store functions (put, get, delete).
    * [Hash](./src/Hash): All the files responsible for the Hash Ring.
    * [Membership](./src/Membership): Everything related to logs and RMI
    * [Server](./src/Server): Code that is used to setup a Service Node registered to a cluster, main component of this project
* [bin](./bin): Compiled Java Code 

## How to execute the code:

To **start** a Service Node run `java -cp ./bin Server.Server <IP_mcast_addr> <IP_mcast_port> <node_id>  <node_port>`
- \<node_id\> IP of the Service Node
- \<node_port\> Node of the Service Node
- <IP_mcast_addr> UDP IP used for Multicast
- <IP_mcast_port> UDP Port used for Multicast

To execute the client run `java -cp ./bin Client.Client <node_id> <node_port> <operation> [<opnd>]`

Supported \<operation\> include
- `join`
- `leave`
- `put` requires a file name
- `get` requires a file name
- `delete` requires a file name
