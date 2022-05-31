package Membership;

import java.rmi.*; 

public interface MembershipInterface extends Remote {  
    public void join() throws RemoteException;
    public void leave() throws RemoteException;  
}  
