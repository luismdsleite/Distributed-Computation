package Membership;

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;

public class MemberShipUtils {

    public static void startRMI(MembershipInterface stub, String id, String name) throws RemoteException, MalformedURLException {
        // Starting an RMI connection
        System.setProperty("java.net.preferIPv4Stack", "true");
        // Setting up RMI
        try{
            LocateRegistry.createRegistry(1099);
        } catch(RemoteException e){
            // Registry is already created
            System.out.println("Registry already exists");
        }
        // Had to add _{id} at the end because for some reason it connects 127.0.0.1 and 127.0.0.2 together
        Naming.rebind("rmi://" + id + ":1099/" + name + "_" + id, stub);
    }

    public static MembershipInterface connectViaRMI(String id, String name) throws MalformedURLException, RemoteException, NotBoundException{
        String objName = "rmi://" + id + ":1099/" + name + "_" + id;
        return (MembershipInterface) Naming.lookup(objName);
    }
}
