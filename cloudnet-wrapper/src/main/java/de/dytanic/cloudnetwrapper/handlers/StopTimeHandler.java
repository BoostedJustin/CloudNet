/*
 * Copyright (c) Tarek Hosni El Alaoui 2017
 */

package de.dytanic.cloudnetwrapper.handlers;

import de.dytanic.cloudnetwrapper.CloudNetWrapper;
import de.dytanic.cloudnetwrapper.server.BungeeCord;
import de.dytanic.cloudnetwrapper.server.CloudGameServer;
import de.dytanic.cloudnetwrapper.server.GameServer;

public final class StopTimeHandler implements IWrapperHandler {

    @Override
    public void run(CloudNetWrapper wrapper)
    {
        for (GameServer gameServer : CloudNetWrapper.getInstance().getServers().values())
        {
            try
            {
                if (!gameServer.isAlive())
                {
                    if(System.currentTimeMillis() > (gameServer.getStartupTimeStamp() + 5000))
                    {
                        gameServer.shutdown();
                    }
                    else
                    {
                        CloudNetWrapper.getInstance().getScheduler().runTaskAsync(new Runnable() {
                            @Override
                            public void run()
                            {
                                gameServer.restart();
                            }
                        });
                    }
                }
            } catch (Exception ex) {

            }
        }

        for (CloudGameServer gameServer : CloudNetWrapper.getInstance().getCloudservers().values())
        {
            try
            {
                if (!gameServer.isAlive())
                    gameServer.shutdown();
            } catch (Exception ex) {

            }
        }

        for (BungeeCord bungeeCord : CloudNetWrapper.getInstance().getProxys().values())
        {
            try
            {
                if (!bungeeCord.isAlive())
                    bungeeCord.shutdown();
            }catch (Exception ex) {

            }
        }

    }

    @Override
    public int getTicks()
    {
        return 4;
    }
}