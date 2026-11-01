package org.redwoodarrows;

import java.io.*;
import org.json.JSONObject;

/** An old server that ignores collection=review must never receive a write. */
public final class SyncCollectionGuardTest {
    interface Request {void run()throws Exception;}
    static void blocked(Request request)throws Exception {
        try{request.run();throw new AssertionError("Legacy server accepted reviewed-library traffic");}
        catch(IOException expected){if(!expected.getMessage().contains("Server update needed"))throw expected;}
    }
    public static void main(String[] args)throws Exception {
        SyncHttp transport=new SyncHttp(args[0],"test-only-token",true,true);
        blocked(()->transport.json("login",new JSONObject().put("username","fixture").put("password","test-only-secret")));
        blocked(()->transport.json("save",new JSONObject()));
        blocked(()->transport.upload("unused",new File("must-not-be-opened.jpg")));
        blocked(()->transport.download("unused",new File("must-not-be-created.jpg"),1));
        System.out.println("Legacy server fail-closed checks passed: 4");
    }
}
