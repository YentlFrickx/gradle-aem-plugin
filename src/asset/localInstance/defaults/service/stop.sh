#!/bin/sh

(cd {{ rootProject.projectDir }} && {{ service.opts['environmentCommand'] }} && {{ service.opts['stopCommand'] }} -Pinstance.name={{ instance.name }})