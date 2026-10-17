package df.root.shizuku.cn;

import android.os.Bundle;
import android.os.IBinder;

// This service only manages the temporary init log setting; it is not a shell API.
interface IInitLogService {
    Bundle snapshot() = 1;
    Bundle begin(in IBinder owner, in String bootId, in String originalValue) = 2;
    Bundle finish(in IBinder owner) = 3;
    Bundle recover(in String bootId, in String originalValue) = 4;
    void destroy() = 16777114;
}
