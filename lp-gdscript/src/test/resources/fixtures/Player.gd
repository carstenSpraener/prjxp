class_name Player
extends CharacterBody2D
## A player that moves and shoots.

@export var speed = 300
var health = 100
signal died

## Moves the player.
func move(dir):
	position += dir * speed

func _ready():
	print("ready")

class AI:
	var brain = 1
